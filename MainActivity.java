package com.ronit.similarsweep;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.*;
import android.view.*;
import android.widget.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final int BG=Color.rgb(16,19,17), CARD=Color.rgb(32,38,33), GREEN=Color.rgb(170,255,112), MUTED=Color.rgb(175,186,177);
    private final ExecutorService worker=Executors.newSingleThreadExecutor(), thumbnails=Executors.newFixedThreadPool(2);
    private final TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ArrayList<Photo> library=new ArrayList<>(), visible=new ArrayList<>(), scored=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private final Map<String,Matcher.Feature> features=new HashMap<>(); // accessed on worker only
    private final LruCache<String,Bitmap> thumbCache=new LruCache<String,Bitmap>(16*1024*1024) {
        @Override protected int sizeOf(String k,Bitmap b) { return b.getByteCount(); }
    };
    private Photo seed;
    private volatile int generation=0;
    private boolean busy=false, results=false, pendingTrash=false;
    private int threshold=80;
    private TextView status, title, summary, thresholdLabel;
    private Button scan, remove, selectAll, libraryButton, access;
    private CheckBox screenshots, duplicates;
    private SeekBar sensitivity;
    private GridView grid;
    private PhotoAdapter adapter;
    private ProgressBar progress;
    private final ArrayList<Uri> trashQueue=new ArrayList<>();
    private ArrayList<Uri> currentBatch=new ArrayList<>();
    private int removedCount=0;
    private boolean started=false;

    static class Photo {
        Uri uri; String name,path,key; long size; double score;
        Photo(Uri u,String n,String p,long s,long modified) { uri=u; name=n; path=p; size=s; key=u+":"+modified; }
        boolean screenshot() { String s=(name+" "+path).toLowerCase(Locale.ROOT); return s.contains("screenshot") || s.contains("screen_shot") || s.contains("screen-shot") || s.contains("screen shot"); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(18),dp(12),dp(18),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)-> { android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); v.setPadding(dp(18)+bars.left,dp(12)+bars.top,dp(18)+bars.right,dp(8)+bars.bottom); return insets; });
        setContentView(root);
        ScrollView controlsScroll=new ScrollView(this);
        LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controlsScroll.addView(controls); root.addView(controlsScroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView brand=text("SIMILAR SWEEP",13,GREEN); brand.setLetterSpacing(.16f); controls.addView(brand);
        title=text("Less clutter.\nMore space.",32,Color.WHITE); title.setTypeface(null,1); controls.addView(title);
        status=text("Choose an example, find similar images, then review and remove in bulk.",15,MUTED); status.setPadding(0,dp(8),0,dp(10)); controls.addView(status);
        LinearLayout tools=row(); controls.addView(tools);
        access=button("Photo access",()->requestAccess()); tools.addView(access,new LinearLayout.LayoutParams(0,dp(48),1));
        libraryButton=button("Choose example",()->showLibrary()); tools.addView(libraryButton,new LinearLayout.LayoutParams(0,dp(48),1));
        screenshots=new CheckBox(this); screenshots.setText("Screenshots only"); screenshots.setTextSize(14); screenshots.setChecked(true); controls.addView(screenshots);
        duplicates=new CheckBox(this); duplicates.setText("Near-duplicates only"); duplicates.setTextSize(14); controls.addView(duplicates);
        screenshots.setOnCheckedChangeListener((v,c)->{ if(!busy) { if(results) resetResults(); else showLibrary(); } });
        duplicates.setOnCheckedChangeListener((v,c)->{ if(results && !busy) resetResults(); });
        thresholdLabel=text("Match strength: 80 · lower finds more",14,MUTED); controls.addView(thresholdLabel);
        sensitivity=new SeekBar(this); sensitivity.setMax(40); sensitivity.setProgress(20); controls.addView(sensitivity);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
            public void onProgressChanged(SeekBar s,int p,boolean user) { threshold=60+p; thresholdLabel.setText("Match strength: "+threshold+" · lower finds more"); if(results && !busy) filterResults(); }
        });
        scan=button("Find similar images",()->{if(busy) cancelScan(); else scan();}); controls.addView(scan);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setVisibility(View.GONE); controls.addView(progress);
        summary=text("Your photos stay on this device.",14,MUTED); summary.setPadding(0,dp(8),0,dp(8)); controls.addView(summary);
        grid=new GridView(this); grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(100)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setHorizontalSpacing(dp(6)); grid.setVerticalSpacing(dp(6));
        adapter=new PhotoAdapter(); grid.setAdapter(adapter); root.addView(grid,new LinearLayout.LayoutParams(-1,0,1.15f));
        grid.setOnItemClickListener((p,v,pos,id)-> { if(busy || pendingTrash) return; Photo photo=visible.get(pos); if(results) { if(!selected.add(photo.key)) selected.remove(photo.key); adapter.notifyDataSetChanged(); updateSelection(); } else { seed=photo; adapter.notifyDataSetChanged(); status.setText("Example: "+photo.name+"\nTap Find similar images to scan."); scan.setEnabled(true); } });
        grid.setOnItemLongClickListener((p,v,pos,id)-> { preview(visible.get(pos)); return true; });
        LinearLayout bottom=row(); root.addView(bottom);
        selectAll=button("Select matches",()-> { if(selected.size()==visible.size()) selected.clear(); else {selected.clear(); for(Photo p:visible) selected.add(p.key);} adapter.notifyDataSetChanged(); updateSelection(); });
        bottom.addView(selectAll,new LinearLayout.LayoutParams(0,dp(52),1));
        remove=button("Trash (0)",()->confirmTrash()); remove.setTextColor(BG); remove.setBackground(tint(GREEN)); bottom.addView(remove,new LinearLayout.LayoutParams(0,dp(52),1));
        updateSelection(); scan.setEnabled(false);
    }
    @Override protected void onResume() { super.onResume(); if(!started) {started=true; if(hasAccess()) loadLibrary();} else if(!pendingTrash && !busy) loadLibrary(); }
    boolean granted(String p) {return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    boolean fullAccess() { return granted(Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE); }
    boolean hasAccess() {return fullAccess() || (Build.VERSION.SDK_INT>=34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED));}
    void requestAccess() {
        if(busy || pendingTrash) return;
        String[] perms=Build.VERSION.SDK_INT>=34?new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}:new String[]{Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE};
        requestPermissions(perms,10);
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) { super.onRequestPermissionsResult(r,p,g); if(r==10) {if(hasAccess()) loadLibrary(); else new AlertDialog.Builder(this).setTitle("Photo access needed").setMessage("Allow photos to find and remove matches. You can change this in app settings.").setPositiveButton("Settings",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("Later",null).show();} }
    void loadLibrary() {
        if(!hasAccess()) { library.clear(); seed=null; showLibrary(); status.setText("Allow photo access to begin."); return; }
        final int token=++generation; setBusy(true); status.setText("Loading photos…");
        worker.execute(()-> {
            ArrayList<Photo> loaded=new ArrayList<>(); String error=null;
            try(Cursor c=getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id","_display_name","relative_path","_size","date_modified"},"is_trashed=0 AND is_pending=0",null,"date_added DESC")) {
                if(c!=null) while(c.moveToNext()) loaded.add(new Photo(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0)),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4)));
            } catch(Exception e) {error="Could not read photos. Check photo access and try again.";}
            final String message=error;
            runOnUiThread(()-> { if(token!=generation || isDestroyed()) return; setBusy(false); library.clear(); library.addAll(loaded); if(seed!=null && loaded.stream().noneMatch(p->p.key.equals(seed.key))) seed=null; showLibrary(); if(message!=null)status.setText(message); });
        });
    }
    void showLibrary() {
        if(busy || pendingTrash) return;
        results=false; scored.clear(); selected.clear(); visible.clear();
        for(Photo p:library) if(!screenshots.isChecked() || p.screenshot()) visible.add(p);
        title.setText("Pick an example"); status.setText(fullAccess()?"Tap an image to use as your example. Hold to preview.":"Limited photo access. Only allowed images can be scanned. Use Photo access to add more.");
        summary.setText(visible.size()+" images"+(screenshots.isChecked()?" · screenshot filenames / folders":" · all accessible photos"));
        if(visible.isEmpty()) status.setText("No images here. Allow photos or turn off Screenshots only.");
        adapter.notifyDataSetChanged(); updateSelection(); scan.setEnabled(seed!=null);
    }
    void resetResults() {showLibrary(); status.setText("Options changed. Tap Find similar images to scan again.");}
    void setBusy(boolean value) { busy=value; access.setEnabled(!value); screenshots.setEnabled(!value); duplicates.setEnabled(!value); libraryButton.setEnabled(!value); sensitivity.setEnabled(!value); scan.setText(value?"Cancel scan":"Find similar images"); scan.setEnabled(value || seed!=null); progress.setVisibility(value?View.VISIBLE:View.GONE); updateSelection(); }
    void cancelScan() { generation++; setBusy(false); results=false; showLibrary(); status.setText("Scan cancelled. No photos changed."); }
    void scan() {
        if(seed==null || pendingTrash) return;
        final Photo reference=seed; final boolean onlyScreens=screenshots.isChecked(), near=duplicates.isChecked(); final int token=++generation;
        final ArrayList<Photo> candidates=new ArrayList<>(); for(Photo p:library) if(!p.key.equals(reference.key) && (!onlyScreens || p.screenshot())) candidates.add(p);
        selected.clear(); scored.clear(); results=false; setBusy(true); title.setText("Finding your matches"); progress.setMax(Math.max(1,candidates.size())); progress.setProgress(0);
        worker.execute(()-> {
            ArrayList<Photo> matches=new ArrayList<>(); int skipped=0, ocrFailed=0;
            try {
                Matcher.Feature example=feature(reference,!near); if(!near && example.text.equals("\u0000")) ocrFailed++;
                for(int i=0;i<candidates.size();i++) {
                    if(token!=generation) return;
                    Photo p=candidates.get(i);
                    try {Matcher.Feature f=feature(p,!near); if(!near && f.text.equals("\u0000")) ocrFailed++; p.score=Matcher.score(example,f,near); matches.add(p);} catch(Exception | OutOfMemoryError e) {skipped++;}
                    final int count=i+1;
                    runOnUiThread(()-> {if(token==generation) {progress.setProgress(count); status.setText("Compared "+count+" of "+candidates.size()+" images · on device");}});
                }
                matches.sort((a,b)->Double.compare(b.score,a.score)); final int missing=skipped, textErrors=ocrFailed;
                runOnUiThread(()-> {if(token!=generation || isDestroyed()) return; setBusy(false); scored.addAll(matches); results=true; title.setText("Review your matches"); status.setText("Tap to select. Hold to preview. Scores are estimates, not certainty."+(missing>0?" "+missing+" unreadable images skipped.":"")+(textErrors>0?" Text recognition failed for "+textErrors+" images; visual matching used.":"")); filterResults();});
            } catch(Exception | OutOfMemoryError e) {runOnUiThread(()->{if(token==generation && !isDestroyed()) {setBusy(false);status.setText("Could not scan this example. Choose another photo or check access.");}});}
        });
    }
    Matcher.Feature feature(Photo p,boolean ocr) throws Exception {
        String key=p.key+(ocr?":text":":visual"); Matcher.Feature cached=features.get(key); if(cached!=null)return cached;
        Bitmap image=decode(p.uri,1280);
        if(image==null)throw new IllegalArgumentException("Unreadable image");
        try {
            Matcher.Feature f=new Matcher.Feature(); f.aspect=image.getWidth()/(float)image.getHeight();
            Bitmap tiny=Bitmap.createScaledBitmap(image,16,16,true); f.layout=new float[16*16*3];
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){int c=tiny.getPixel(x,y),i=(y*16+x)*3;f.layout[i]=Color.red(c)/255f;f.layout[i+1]=Color.green(c)/255f;f.layout[i+2]=Color.blue(c)/255f;} if(tiny!=image)tiny.recycle();
            Bitmap hashImage=Bitmap.createScaledBitmap(image,9,8,true); long h=0;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++) {int a=hashImage.getPixel(x,y),b=hashImage.getPixel(x+1,y); if(luma(a)>luma(b))h|=1L<<(y*8+x);} f.hash=h; if(hashImage!=image)hashImage.recycle();
            if(ocr) {
                // Await completion before recycling the bitmap passed to ML Kit.
                try {f.text=Tasks.await(recognizer.process(InputImage.fromBitmap(image,0))).getText(); f.app=Matcher.appHint(f.text);} catch(Exception e) {f.text="\u0000";}
            }
            if(features.size()>12000) features.clear(); features.put(key,f); return f;
        } finally {image.recycle();}
    }
    double luma(int c){return .299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c);}
    Bitmap decode(Uri uri,int bound) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,source)-> {int w=info.getSize().getWidth(),h=info.getSize().getHeight(); float scale=Math.min(1f,bound/(float)Math.max(w,h)); decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale))); decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
    }
    void filterResults() {visible.clear(); selected.clear(); for(Photo p:scored)if(p.score>=threshold) visible.add(p); summary.setText(visible.size()+" matches · example excluded · hold to preview"); adapter.notifyDataSetChanged();updateSelection();}
    void updateSelection() {
        if(remove==null)return; long bytes=0;for(Photo p:visible)if(selected.contains(p.key))bytes+=p.size;
        remove.setText("Trash ("+selected.size()+")"); remove.setEnabled(!busy && !pendingTrash && !selected.isEmpty()); selectAll.setEnabled(results && !busy && !pendingTrash && !visible.isEmpty()); selectAll.setText(!selected.isEmpty() && selected.size()==visible.size()?"Clear selection":"Select matches");
        if(results && !selected.isEmpty()) summary.setText(selected.size()+" selected · "+android.text.format.Formatter.formatFileSize(this,bytes)+" · held in Trash until deleted");
        else if(results) summary.setText(visible.size()+" matches · example excluded · hold to preview");
    }
    void confirmTrash() {
        if(selected.isEmpty() || busy || pendingTrash)return;
        new AlertDialog.Builder(this).setTitle("Move "+selected.size()+" images to Trash?").setMessage("Review your selection first. Android will ask for confirmation. The example is kept. Trashed images may be restored using your phone’s gallery before they expire.").setNegativeButton("Keep reviewing",null).setPositiveButton("Continue",(d,w)-> {
            trashQueue.clear(); for(Photo p:visible) if(selected.contains(p.key) && (seed==null || !p.key.equals(seed.key)))trashQueue.add(p.uri);
            removedCount=0; pendingTrash=true; updateSelection(); nextTrashBatch();
        }).show();
    }
    void nextTrashBatch() {
        if(trashQueue.isEmpty()){finishTrash("Moved "+removedCount+" images to Trash.");return;}
        currentBatch=new ArrayList<>(trashQueue.subList(0,Math.min(500,trashQueue.size())));
        try {startIntentSenderForResult(MediaStore.createTrashRequest(getContentResolver(),currentBatch,true).getIntentSender(),20,null,0,0,0);}
        catch(Exception e){finishTrash("Could not request removal. "+removedCount+" images moved; remaining images unchanged.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20) {if(result==RESULT_OK) {removedCount+=currentBatch.size(); trashQueue.removeAll(currentBatch); nextTrashBatch();} else finishTrash("Cancelled. "+removedCount+" images moved to Trash; remaining images unchanged.");}
    }
    void finishTrash(String message){pendingTrash=false;trashQueue.clear();selected.clear();Toast.makeText(this,message,Toast.LENGTH_LONG).show();loadLibrary();}
    void preview(Photo p) {
        ImageView image=new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setMinimumHeight(dp(300));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(p.name).setView(image).setPositiveButton("Close",null).create(); dialog.show();
        thumbnails.execute(()->{try {Bitmap b=decode(p.uri,1600);runOnUiThread(()->{if(dialog.isShowing() && !isDestroyed())image.setImageBitmap(b);});}catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(dialog.isShowing())dialog.setMessage("Preview unavailable. Photo may have moved or access changed.");});}});
    }
    class PhotoAdapter extends BaseAdapter {
        public int getCount(){return visible.size();} public Object getItem(int p){return visible.get(p);} public long getItemId(int p){return p;}
        public View getView(int pos,View old,ViewGroup parent) {
            FrameLayout tile; ImageView image; TextView badge;
            if(old==null){tile=new FrameLayout(MainActivity.this); tile.setLayoutParams(new AbsListView.LayoutParams(-1,dp(140)));image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(image,new FrameLayout.LayoutParams(-1,-1));badge=text("",13,Color.WHITE);badge.setPadding(dp(6),dp(4),dp(6),dp(4));badge.setBackgroundColor(0xdd101311);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);tile.addView(badge,bp);}
            else{tile=(FrameLayout)old;image=(ImageView)tile.getChildAt(0);badge=(TextView)tile.getChildAt(1);}
            Photo p=visible.get(pos); boolean chosen=results?selected.contains(p.key):(seed!=null && seed.key.equals(p.key));
            tile.setPadding(dp(chosen?4:0),dp(chosen?4:0),dp(chosen?4:0),dp(chosen?4:0));tile.setBackgroundColor(chosen?GREEN:CARD);badge.setText(results?(chosen?"✓  ":"")+Math.round(p.score)+" match":chosen?"EXAMPLE":p.name); image.setContentDescription(p.name+(chosen?", selected":"")); image.setTag(p.key); image.setImageBitmap(null);
            Bitmap hit=thumbCache.get(p.key);if(hit!=null)image.setImageBitmap(hit);else thumbnails.execute(()->{if(!p.key.equals(image.getTag()))return;try {Bitmap b=getContentResolver().loadThumbnail(p.uri,new Size(320,320),null);thumbCache.put(p.key,b);runOnUiThread(()->{if(p.key.equals(image.getTag()) && !isDestroyed())image.setImageBitmap(b);});}catch(Exception|OutOfMemoryError ignored){}});
            return tile;
        }
    }
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    GradientDrawable tint(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(GREEN);b.setBackground(tint(CARD));b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
    @Override public void onBackPressed(){if(busy){cancelScan();return;}if(results){showLibrary();return;}super.onBackPressed();}
    @Override protected void onDestroy(){generation++;worker.execute(()->recognizer.close());worker.shutdown();thumbnails.shutdownNow();super.onDestroy();}
}

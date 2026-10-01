import com.ronit.similarsweep.Matcher;
import java.util.Arrays;
public class MatcherTest {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static Matcher.Feature feature(float tone,long hash,String text){Matcher.Feature f=new Matcher.Feature();f.layout=new float[768];Arrays.fill(f.layout,tone);f.hash=hash;f.aspect=.5f;f.text=text;f.app=Matcher.appHint(text);return f;}
    public static void main(String[] args){
        var youtube=feature(.2f,0x12345678L,"YouTube Subscribe Shorts Views");
        var sameApp=feature(.7f,0xffeeddccL,"YouTube different channel");
        var discord=feature(.2f,0x12345678L,"Discord direct messages servers");
        check(Matcher.score(youtube,sameApp,false)>=80,"Same-app text should survive different content");
        check(Matcher.score(youtube,discord,false)<=60,"Conflicting app hints must suppress dark-layout false matches");
        check(Matcher.score(youtube,youtube,true)>99.9,"Identical features should match");
        var opposite=feature(1f,~youtube.hash,"unrelated");
        check(Matcher.score(youtube,opposite,true)<60,"Visually dissimilar image must rank low");
        check(Matcher.appHint("a photo of a bird").isEmpty(),"Generic images must not gain an app hint");
        check(Matcher.appHint("For you Following Grok").equals("X / Twitter"),"X chrome recognition");
        check(Matcher.appHint("Likes only").isEmpty(),"One generic word is not enough");
        check(Matcher.score(youtube,opposite,false)>=0 && Matcher.score(youtube,opposite,false)<=100,"Bounds");
        System.out.println("PASS: 8 matcher checks (synthetic data; not real-world accuracy validation)");
    }
}

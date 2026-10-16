import io.github.hanshs474.emaki.Emaki;
import io.github.hanshs474.emaki.EmakiException;

/**
 * Two live checks that spend nothing: the balance with a real key, and a submit with a wrong key.
 * EMAKI_API_KEY=sk-... java -cp emaki-0.1.0.jar example/Smoke.java
 */
public class Smoke {
    public static void main(String[] args) {
        int credits = new Emaki().credits();
        System.out.println("credits() ok: " + (credits >= 0));
        try {
            new Emaki("sk-not-a-real-key").submit("a quiet harbor at dawn", Emaki.MODEL_IMAGE, null);
            System.out.println("wrong key accepted?!");
            System.exit(1);
        } catch (EmakiException e) {
            System.out.println("wrong key -> " + e.kind() + " (HTTP " + e.httpStatus() + ")");
            if (e.kind() != EmakiException.Kind.AUTH) System.exit(1);
        }
    }
}

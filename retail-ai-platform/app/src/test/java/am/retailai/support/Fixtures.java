package am.retailai.support;

import java.io.IOException;
import java.io.InputStream;

public final class Fixtures {
    private Fixtures() {
    }

    public static byte[] bytes(String path) throws IOException {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("missing fixture " + path);
            return in.readAllBytes();
        }
    }
}

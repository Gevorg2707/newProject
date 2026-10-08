package am.retailai.parse;

import java.io.IOException;

/** The file is not a valid MT940 statement, or its balances do not add up. The whole file is rejected. */
public class Mt940FormatException extends IOException {
    public Mt940FormatException(String message) {
        super(message);
    }
}

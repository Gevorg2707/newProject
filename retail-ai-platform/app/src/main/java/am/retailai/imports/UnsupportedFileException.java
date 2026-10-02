package am.retailai.imports;

public class UnsupportedFileException extends RuntimeException {
    public UnsupportedFileException(String fileName) {
        super("Unsupported file type: " + fileName + " (supported: .csv, .xlsx, .xls)");
    }
}

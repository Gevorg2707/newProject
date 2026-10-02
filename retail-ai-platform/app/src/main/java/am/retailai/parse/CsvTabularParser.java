package am.retailai.parse;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Component
public class CsvTabularParser implements TabularParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset(); // no BOM
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .get();
        List<ParsedRow> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            List<String> headers = parser.getHeaderNames();
            for (CSVRecord rec : parser) {
                var cells = new LinkedHashMap<String, String>();
                for (String h : headers) {
                    cells.put(h, rec.isMapped(h) && rec.isSet(h) ? rec.get(h) : "");
                }
                rows.add(new ParsedRow((int) rec.getRecordNumber() + 1, cells));
            }
        }
        return rows;
    }
}

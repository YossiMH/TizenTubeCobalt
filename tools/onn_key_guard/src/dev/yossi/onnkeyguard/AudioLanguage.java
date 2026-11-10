package dev.yossi.onnkeyguard;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure language/variant parsing. This does not establish that a UI row is an audio row. */
public final class AudioLanguage {
    public enum Language { HEBREW, OTHER, UNKNOWN }
    public enum Variant { STANDARD, AUDIO_DESCRIPTION, UNKNOWN }

    public static final class Parsed {
        public final Language language;
        public final Variant variant;

        private Parsed(Language language, Variant variant) {
            this.language = language;
            this.variant = variant;
        }
    }

    private static final Pattern BIDI = Pattern.compile("[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*");
    private static final Pattern HEBREW_START = Pattern.compile("^(?:hebrew|עברית|he|iw|heb)(?=$|[\\s\\[\\(\\-–—:])");
    private static final Pattern WRAPPERS = Pattern.compile("[\\[\\](){}:,\\-–—]");
    private static final Pattern AD = Pattern.compile("(?:^|\\s)(?:audio descriptions?|descriptive audio|ad|תיאור קולי|תיאור שמע)(?=$|\\s)");
    private static final Pattern MODIFIERS = Pattern.compile(
            "(?:^|\\s)(?:original|standard|default|selected|stereo|surround|dolby atmos|"
            + "dolby digital plus|dolby digital|dolby|atmos|aac|eac3|e-ac-3|ac3|ac-3|"
            + "2\\.0|5\\.1|7\\.1|מקורי|מקור|נבחרה|נבחר|סטריאו)(?=$|\\s)");

    private AudioLanguage() { }

    public static Parsed parse(String languageTag, String displayLabel) {
        String tag = normalize(languageTag);
        Parsed label = parseLabel(displayLabel);
        if (tag.length() == 0 || !TAG.matcher(tag).matches()) {
            return label;
        }
        String base = tag.split("[-_]", 2)[0];
        Language tagged = base.equals("he") || base.equals("iw") || base.equals("heb")
                ? Language.HEBREW : Language.OTHER;
        // A contradictory explicit code and recognized Hebrew label are unsafe evidence.
        if (tagged == Language.OTHER && label.language == Language.HEBREW) {
            return new Parsed(Language.UNKNOWN, Variant.UNKNOWN);
        }
        if (tagged == Language.HEBREW) {
            String descriptor = normalize(WRAPPERS.matcher(normalize(displayLabel)).replaceAll(" "));
            Variant variant = label.language == Language.HEBREW ? label.variant
                    : AD.matcher(descriptor).find() ? Variant.AUDIO_DESCRIPTION : Variant.UNKNOWN;
            return new Parsed(tagged, variant);
        }
        return new Parsed(tagged, Variant.UNKNOWN);
    }

    public static Parsed parseLabel(String displayLabel) {
        String text = normalize(displayLabel);
        // Checked markers are presentation; an adapter must still verify row selection separately.
        text = text.replaceFirst("^[✓✔☑●]\\s*", "");
        java.util.regex.Matcher start = HEBREW_START.matcher(text);
        if (!start.find()) {
            return new Parsed(Language.UNKNOWN, Variant.UNKNOWN);
        }
        String suffix = normalize(WRAPPERS.matcher(text.substring(start.end())).replaceAll(" "));
        Variant variant = AD.matcher(suffix).find() ? Variant.AUDIO_DESCRIPTION : Variant.STANDARD;
        suffix = normalize(AD.matcher(suffix).replaceAll(" "));
        String prior;
        do {
            prior = suffix;
            suffix = normalize(MODIFIERS.matcher(suffix).replaceAll(" "));
        } while (!prior.equals(suffix));
        // Do not turn group headings, subtitles, unavailability messages, or prose into tracks.
        if (suffix.length() != 0) {
            return new Parsed(Language.UNKNOWN, Variant.UNKNOWN);
        }
        return new Parsed(Language.HEBREW, variant);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        normalized = BIDI.matcher(normalized).replaceAll("");
        return SPACES.matcher(normalized).replaceAll(" ").trim();
    }
}

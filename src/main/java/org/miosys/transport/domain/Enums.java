package org.miosys.transport.domain;

import java.util.Locale;

/** Lowercase wire values; parse inputs are already validated. */
public final class Enums {

    private Enums() {}

    public enum Region {
        LOCAL,
        TRAVEL;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Region parse(String value) {
            return Region.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum Purpose {
        PERSONAL,
        BUSINESS;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Purpose parse(String value) {
            return Purpose.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum Side {
        BASELINE,
        CURRENT;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Side parse(String value) {
            return Side.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum Period {
        WEEKLY,
        MONTHLY;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Period parse(String value) {
            return Period.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}

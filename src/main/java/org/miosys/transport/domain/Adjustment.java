package org.miosys.transport.domain;

import java.time.LocalDate;
import java.util.Optional;

/** Money tied to a rental. affectsNet derives from kind; the API never takes it as input. */
public sealed interface Adjustment
        permits Adjustment.Rebate, Adjustment.Refund, Adjustment.Reimbursement, Adjustment.Hold {

    int lineNo();

    long amountCents();

    LocalDate effectiveDate();

    Optional<String> emailMessageId();

    Optional<String> notes();

    boolean affectsNet();

    String kind();

    record Rebate(
            int lineNo,
            long amountCents,
            LocalDate effectiveDate,
            Optional<String> emailMessageId,
            Optional<String> notes)
            implements Adjustment {
        @Override
        public boolean affectsNet() {
            return true;
        }

        @Override
        public String kind() {
            return "rebate";
        }
    }

    record Refund(
            int lineNo,
            long amountCents,
            LocalDate effectiveDate,
            Optional<String> emailMessageId,
            Optional<String> notes)
            implements Adjustment {
        @Override
        public boolean affectsNet() {
            return true;
        }

        @Override
        public String kind() {
            return "refund";
        }
    }

    record Reimbursement(
            int lineNo,
            long amountCents,
            LocalDate effectiveDate,
            Optional<String> emailMessageId,
            Optional<String> notes)
            implements Adjustment {
        @Override
        public boolean affectsNet() {
            return false;
        }

        @Override
        public String kind() {
            return "reimbursement";
        }
    }

    record Hold(
            int lineNo,
            long amountCents,
            LocalDate effectiveDate,
            boolean released,
            Optional<String> emailMessageId,
            Optional<String> notes)
            implements Adjustment {
        @Override
        public boolean affectsNet() {
            return false;
        }

        @Override
        public String kind() {
            return "hold";
        }
    }
}

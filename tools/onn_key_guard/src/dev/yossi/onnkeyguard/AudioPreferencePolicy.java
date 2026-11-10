package dev.yossi.onnkeyguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Event-driven, Android-independent audio policy. No UI traversal, shell, network, or timers.
 * A caller supplies app-verified audio-menu snapshots and executes a returned selection once.
 * Unknown attribution fails open. Pause/resume must not call endItem().
 */
public final class AudioPreferencePolicy {
    public enum Origin { PHYSICAL_REMOTE, SOFTWARE_REMOTE, ASSISTANT, UNKNOWN }
    public enum RowKind { AUDIO, SUBTITLE, UNKNOWN }
    public enum Availability { ENABLED, DISABLED, UNKNOWN }
    public enum MenuEvidence { VERIFIED_AUDIO_MENU, UNVERIFIED }
    public enum MenuExtent { COMPLETE, PARTIAL }
    public enum Phase { NO_ITEM, HEBREW_DEFAULT, SOFTWARE_OVERRIDE, UNRESOLVED_ATTRIBUTION }
    public enum Action { NONE, SELECT_AUDIO }
    public enum ConfirmationResult { RECORDED, IGNORED }
    public enum Reason {
        NO_ITEM, DIFFERENT_ITEM, UNVERIFIED_MENU, STALE_SNAPSHOT, NO_SELECTED_AUDIO,
        NO_HEBREW_AVAILABLE, HEBREW_NOT_YET_VISIBLE, OVERRIDE_TARGET_NOT_VISIBLE,
        TARGET_ALREADY_SELECTED, WAITING_FOR_USER_SELECTION, WAITING_FOR_AUTOMATIC_SELECTION,
        UNRESOLVED_ATTRIBUTION, ATTEMPTS_EXHAUSTED, SELECT_HEBREW, RESTORE_SOFTWARE_OVERRIDE
    }

    public static final long CONFIRMATION_MAX_AGE_MS = 3000;
    public static final long AUTOMATIC_ACK_TIMEOUT_MS = 3000;
    public static final int MAX_AUTOMATIC_ATTEMPTS_PER_INCIDENT = 2;
    public static final int MAX_MENU_ROWS = 256;

    public static final class Item {
        public final String packageName;
        public final String contentId;

        public Item(String packageName, String contentId) {
            this.packageName = requireText(packageName, "packageName");
            this.contentId = requireText(contentId, "contentId");
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Item)) {
                return false;
            }
            Item item = (Item) other;
            return packageName.equals(item.packageName) && contentId.equals(item.contentId);
        }

        @Override public int hashCode() {
            return 31 * packageName.hashCode() + contentId.hashCode();
        }
    }

    public static final class Option {
        /** Stable within an item, including across menu reconstruction; not a transient node ID. */
        public final String id;
        public final RowKind kind;
        public final Availability availability;
        public final AudioLanguage.Parsed language;

        public Option(String id, RowKind kind, Availability availability, String tag, String label) {
            this.id = requireText(id, "option id");
            this.kind = require(kind, "kind");
            this.availability = require(availability, "availability");
            this.language = AudioLanguage.parse(tag, label);
        }
    }

    public static final class MenuSnapshot {
        public final Item item;
        public final int windowId;
        /** Strictly increasing for fresh captured evidence, never merely for reevaluating a cache. */
        public final long sequence;
        public final MenuEvidence evidence;
        public final MenuExtent extent;
        public final List<Option> options;
        /** Empty when selected AUDIO state is not positively identified. */
        public final String selectedId;

        public MenuSnapshot(Item item, int windowId, long sequence, MenuEvidence evidence,
                MenuExtent extent, List<Option> options, String selectedId) {
            this.item = require(item, "item");
            this.windowId = windowId;
            if (sequence < 0) {
                throw new IllegalArgumentException("negative sequence");
            }
            this.sequence = sequence;
            this.evidence = require(evidence, "evidence");
            this.extent = require(extent, "extent");
            require(options, "options");
            if (options.size() > MAX_MENU_ROWS) {
                throw new IllegalArgumentException("too many menu rows");
            }
            Set<String> ids = new HashSet<String>();
            ArrayList<Option> copy = new ArrayList<Option>();
            for (Option option : options) {
                require(option, "option");
                if (!ids.add(option.id)) {
                    throw new IllegalArgumentException("duplicate option id");
                }
                copy.add(option);
            }
            this.options = Collections.unmodifiableList(copy);
            this.selectedId = selectedId == null ? "" : selectedId;
        }

        private Option find(String id) {
            for (Option option : options) {
                if (option.id.equals(id)) {
                    return option;
                }
            }
            return null;
        }
    }

    public static final class Decision {
        public final Action action;
        public final Reason reason;
        public final String targetId;
        public final long generation;

        private Decision(Action action, Reason reason, String targetId, long generation) {
            this.action = action;
            this.reason = reason;
            this.targetId = targetId;
            this.generation = generation;
        }
    }

    public static final class Status {
        public final Phase phase;
        public final String preferredId;
        public final String selectedId;
        public final int automaticAttempts;
        public final long generation;

        private Status(Phase phase, String preferredId, String selectedId, int attempts, long generation) {
            this.phase = phase;
            this.preferredId = preferredId;
            this.selectedId = selectedId;
            this.automaticAttempts = attempts;
            this.generation = generation;
        }
    }

    private static final class PendingConfirmation {
        final int windowId;
        final long afterSequence;
        final String optionId;
        final Origin origin;
        final long at;

        PendingConfirmation(int windowId, long afterSequence, String optionId, Origin origin, long at) {
            this.windowId = windowId;
            this.afterSequence = afterSequence;
            this.optionId = optionId;
            this.origin = origin;
            this.at = at;
        }
    }

    private static final class PendingAutomatic {
        final int windowId;
        final long afterSequence;
        final String optionId;
        final long at;

        PendingAutomatic(int windowId, long afterSequence, String optionId, long at) {
            this.windowId = windowId;
            this.afterSequence = afterSequence;
            this.optionId = optionId;
            this.at = at;
        }
    }

    private Item item;
    private Phase phase = Phase.NO_ITEM;
    private MenuSnapshot latest;
    private String preferredId = "";
    private String selectedId = "";
    private PendingConfirmation confirmation;
    private PendingAutomatic automatic;
    private int attempts;
    private long generation;
    private long lastSequence = -1;

    /** Same content ID is idempotent, preserving overrides across pause/resume and activity churn. */
    public synchronized void beginItem(Item newItem) {
        require(newItem, "item");
        if (newItem.equals(item)) {
            return;
        }
        reset();
        item = newItem;
        phase = Phase.HEBREW_DEFAULT;
    }

    /** Call only for a positively identified playback end, never just for a pause or missing callback. */
    public synchronized void endItem() {
        reset();
    }

    /**
     * Invalidate active-menu evidence when its window closes or focus leaves its app.
     * This preserves an established per-item override; it never signifies a playback end.
     */
    public synchronized void invalidateMenu() {
        latest = null;
        confirmation = null;
        automatic = null;
        generation++;
    }

    /**
     * Only call for a non-repeated confirming key on a positively identified focused audio row.
     * Own automatic clicks must not be passed here as ASSISTANT input.
     * Root's adapter owns key-code/repeat matching and physical key-down/key-up pairing.
     */
    public synchronized ConfirmationResult confirmAudioSelection(int windowId, String optionId,
            Origin origin, long uptimeMs) {
        require(origin, "origin");
        if (latest == null || phase == Phase.NO_ITEM || uptimeMs < 0
                || latest.windowId != windowId || latest.evidence != MenuEvidence.VERIFIED_AUDIO_MENU) {
            return ConfirmationResult.IGNORED;
        }
        Option option = latest.find(optionId);
        if (option == null || option.kind != RowKind.AUDIO || option.availability != Availability.ENABLED) {
            return ConfirmationResult.IGNORED;
        }
        Origin attributed = origin;
        // Mixed sources before selection acknowledgement cannot be safely attributed to either.
        if (confirmation != null && within(uptimeMs, confirmation.at, CONFIRMATION_MAX_AGE_MS)
                && confirmation.origin != origin) {
            attributed = Origin.UNKNOWN;
        }
        confirmation = new PendingConfirmation(windowId, latest.sequence, option.id, attributed, uptimeMs);
        automatic = null;
        generation++; // Invalidate any automatic click that the caller has queued but not executed.
        return ConfirmationResult.RECORDED;
    }

    public synchronized Decision observeMenu(MenuSnapshot snapshot, long uptimeMs) {
        require(snapshot, "snapshot");
        if (phase == Phase.NO_ITEM) {
            return none(Reason.NO_ITEM);
        }
        if (!snapshot.item.equals(item)) {
            invalidateMenu();
            return none(Reason.DIFFERENT_ITEM);
        }
        if (snapshot.evidence != MenuEvidence.VERIFIED_AUDIO_MENU || uptimeMs < 0) {
            invalidateMenu();
            return none(Reason.UNVERIFIED_MENU);
        }
        if (snapshot.sequence <= lastSequence) {
            return none(Reason.STALE_SNAPSHOT);
        }
        lastSequence = snapshot.sequence;
        latest = snapshot;
        Option selected = snapshot.find(snapshot.selectedId);
        if (selected == null || selected.kind != RowKind.AUDIO) {
            generation++; // A queued click must not execute against incomplete replacement evidence.
            return none(Reason.NO_SELECTED_AUDIO);
        }

        if (confirmation != null
                && snapshot.windowId == confirmation.windowId
                && snapshot.sequence > confirmation.afterSequence
                && selected.id.equals(confirmation.optionId)
                && within(uptimeMs, confirmation.at, CONFIRMATION_MAX_AGE_MS)) {
            applyConfirmation(selected);
        } else if (automatic != null
                && snapshot.windowId == automatic.windowId
                && snapshot.sequence > automatic.afterSequence
                && selected.id.equals(automatic.optionId)
                && within(uptimeMs, automatic.at, AUTOMATIC_ACK_TIMEOUT_MS)) {
            automatic = null;
            attempts = 0;
            generation++;
        } else if (selectedId.length() != 0 && !selectedId.equals(selected.id)) {
            // A recent volume/navigation key is never sufficient evidence of who changed audio.
            phase = Phase.UNRESOLVED_ATTRIBUTION;
            preferredId = selected.id;
            confirmation = null;
            automatic = null;
            generation++;
        }
        selectedId = selected.id;

        if (confirmation != null) {
            if (within(uptimeMs, confirmation.at, CONFIRMATION_MAX_AGE_MS)) {
                return none(Reason.WAITING_FOR_USER_SELECTION);
            }
            confirmation = null;
        }
        if (phase == Phase.UNRESOLVED_ATTRIBUTION) {
            return none(Reason.UNRESOLVED_ATTRIBUTION);
        }
        Option target = phase == Phase.SOFTWARE_OVERRIDE ? snapshot.find(preferredId) : bestHebrew(snapshot);
        if (target == null || target.kind != RowKind.AUDIO || target.availability != Availability.ENABLED) {
            if (phase == Phase.SOFTWARE_OVERRIDE) {
                return none(Reason.OVERRIDE_TARGET_NOT_VISIBLE);
            }
            return none(snapshot.extent == MenuExtent.COMPLETE
                    ? Reason.NO_HEBREW_AVAILABLE : Reason.HEBREW_NOT_YET_VISIBLE);
        }
        if (target.id.equals(selected.id)) {
            automatic = null;
            attempts = 0;
            return none(Reason.TARGET_ALREADY_SELECTED);
        }
        if (automatic != null) {
            if (within(uptimeMs, automatic.at, AUTOMATIC_ACK_TIMEOUT_MS)) {
                return none(Reason.WAITING_FOR_AUTOMATIC_SELECTION);
            }
            automatic = null;
        }
        if (attempts >= MAX_AUTOMATIC_ATTEMPTS_PER_INCIDENT) {
            return none(Reason.ATTEMPTS_EXHAUSTED);
        }
        attempts++;
        automatic = new PendingAutomatic(snapshot.windowId, snapshot.sequence, target.id, uptimeMs);
        generation++;
        return new Decision(Action.SELECT_AUDIO, phase == Phase.SOFTWARE_OVERRIDE
                ? Reason.RESTORE_SOFTWARE_OVERRIDE : Reason.SELECT_HEBREW, target.id, generation);
    }

    /** Check immediately before executing a queued action, on the same serialized event handler. */
    public synchronized boolean isCurrent(Decision decision) {
        if (decision == null || decision.action != Action.SELECT_AUDIO
                || decision.generation != generation || automatic == null || latest == null
                || latest.windowId != automatic.windowId
                || !automatic.optionId.equals(decision.targetId)) {
            return false;
        }
        Option target = latest.find(decision.targetId);
        Option selected = latest.find(latest.selectedId);
        return target != null && target.kind == RowKind.AUDIO
                && target.availability == Availability.ENABLED
                && selected != null && selected.kind == RowKind.AUDIO;
    }

    public synchronized Status status() {
        return new Status(phase, preferredId, selectedId, attempts, generation);
    }

    private void applyConfirmation(Option selected) {
        Origin origin = confirmation.origin;
        confirmation = null;
        automatic = null;
        attempts = 0;
        if (origin == Origin.SOFTWARE_REMOTE || origin == Origin.ASSISTANT) {
            phase = Phase.SOFTWARE_OVERRIDE;
            preferredId = selected.id;
        } else if (origin == Origin.UNKNOWN) {
            phase = Phase.UNRESOLVED_ATTRIBUTION;
            preferredId = selected.id;
        }
        // PHYSICAL_REMOTE keeps the existing default or explicit phone target.
        generation++;
    }

    private Option bestHebrew(MenuSnapshot snapshot) {
        Option best = null;
        for (Option option : snapshot.options) {
            if (option.kind != RowKind.AUDIO || option.availability != Availability.ENABLED
                    || option.language.language != AudioLanguage.Language.HEBREW) {
                continue;
            }
            if (best == null || rank(option.language.variant) < rank(best.language.variant)) {
                best = option;
            }
        }
        return best;
    }

    private static int rank(AudioLanguage.Variant variant) {
        switch (variant) {
            case STANDARD: return 0;
            case UNKNOWN: return 1;
            case AUDIO_DESCRIPTION: return 2;
            default: throw new AssertionError(variant);
        }
    }

    private Decision none(Reason reason) {
        return new Decision(Action.NONE, reason, "", generation);
    }

    private void reset() {
        item = null;
        phase = Phase.NO_ITEM;
        latest = null;
        preferredId = "";
        selectedId = "";
        confirmation = null;
        automatic = null;
        attempts = 0;
        lastSequence = -1;
        generation++;
    }

    private static boolean within(long now, long then, long maxAge) {
        return now >= then && now - then <= maxAge;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        return value;
    }
}

package dev.yossi.onnkeyguard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.yossi.onnkeyguard.AudioPreferencePolicy.Action;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Availability;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.ConfirmationResult;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Decision;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Item;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.MenuEvidence;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.MenuExtent;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.MenuSnapshot;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Option;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Origin;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Phase;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.Reason;
import dev.yossi.onnkeyguard.AudioPreferencePolicy.RowKind;

/** Executes real policy transitions. No Android/UI substitutes or external test dependencies. */
public final class AudioPreferencePolicyTest {
    private static int assertions;
    private static final Item ITEM = new Item("com.netflix.ninja", "episode-1");
    private static final Option EN = audio("english", "en", "English");
    private static final Option ES = audio("spanish", "es", "Spanish");
    private static final Option HE = audio("hebrew", "he", "עברית");
    private static final Option HE_AD = audio("hebrew-ad", "he", "Hebrew [Audio Description]");
    private static final List<Option> OPTIONS = Arrays.asList(EN, ES, HE_AD, HE);

    public static void main(String[] args) {
        languageParsing();
        startsHebrewAndAcknowledges();
        missingHebrewAndSubtitles();
        physicalChangesRevert();
        phoneOverrideSurvivesPauseAndPhysicalChanges();
        assistantOverrideAndNextItem();
        unknownAndStaleAttributionPreserved();
        mixedSourcesPreserved();
        queuedAutomaticActionCancelled();
        boundedRetries();
        invalidMenusAndConfirmations();
        menuIdentityAndFreshEvidence();
        staleWindowsCancelActionsWithoutResettingOverrides();
        boundedInputData();
        System.out.println("PASS AudioPreferencePolicyTest: " + assertions + " assertions");
    }

    private static void languageParsing() {
        for (String tag : Arrays.asList("he", "he-IL", "iw", "iw_IL", "heb", "HE-il")) {
            equal(AudioLanguage.Language.HEBREW, AudioLanguage.parse(tag, "").language, tag);
        }
        for (String label : Arrays.asList("Hebrew", "עברית", "\u200fעברית\u200f", "✓ Hebrew",
                "Hebrew [Original] (5.1)", "Hebrew – Dolby Atmos", "עברית (מקורי)", "heb")) {
            equal(AudioLanguage.Language.HEBREW, AudioLanguage.parse("", label).language, label);
            equal(AudioLanguage.Variant.STANDARD, AudioLanguage.parse("", label).variant, label);
        }
        for (String label : Arrays.asList("Hebrew [Audio Description]", "Hebrew (AD)",
                "עברית – תיאור קולי", "Hebrew – Audio Description (5.1)")) {
            equal(AudioLanguage.Variant.AUDIO_DESCRIPTION, AudioLanguage.parse("", label).variant, label);
        }
        equal(AudioLanguage.Variant.AUDIO_DESCRIPTION,
                AudioLanguage.parse("he", "Audio Description").variant, "tag with AD-only label");
        equal(AudioLanguage.Variant.UNKNOWN, AudioLanguage.parse("he", "").variant, "code without role");
        for (String label : Arrays.asList("Non-Hebrew", "Hebrew-ish", "Hebrew subtitles", "Hebrew unavailable",
                "Audio: Hebrew / Subtitles: English", "Languages Hebrew English", "Hebrew (CC)", "")) {
            equal(AudioLanguage.Language.UNKNOWN, AudioLanguage.parse("", label).language, label);
        }
        equal(AudioLanguage.Language.UNKNOWN, AudioLanguage.parse("en", "Hebrew").language,
                "contradictory code and label");
        equal(AudioLanguage.Language.OTHER, AudioLanguage.parse("en-US", "English").language, "English code");
        equal(AudioLanguage.Language.HEBREW, AudioLanguage.parse("garbage value", "Hebrew").language,
                "invalid code can use precise label");
    }

    private static void startsHebrewAndAcknowledges() {
        AudioPreferencePolicy p = policy();
        Decision action = p.observeMenu(menu(ITEM, 1, "english"), 100);
        select(action, "hebrew", Reason.SELECT_HEBREW);
        truth(p.isCurrent(action), "fresh automatic decision");
        none(p.observeMenu(menu(ITEM, 2, "english"), 200), Reason.WAITING_FOR_AUTOMATIC_SELECTION);
        none(p.observeMenu(menu(ITEM, 3, "hebrew"), 250), Reason.TARGET_ALREADY_SELECTED);
        equal(Phase.HEBREW_DEFAULT, p.status().phase, "automatic action is not manual override");
        equal(0, p.status().automaticAttempts, "success resets retry budget");
        truth(!p.isCurrent(action), "acknowledged action cannot execute again");
        none(p.observeMenu(menu(ITEM, 4, "hebrew"), 10000), Reason.TARGET_ALREADY_SELECTED);

        AudioPreferencePolicy ad = policy();
        select(ad.observeMenu(menu(ITEM, 1, "english", Arrays.asList(EN, HE_AD)), 100),
                "hebrew-ad", Reason.SELECT_HEBREW);
    }

    private static void missingHebrewAndSubtitles() {
        AudioPreferencePolicy p = policy();
        Option subtitle = new Option("subtitle-he", RowKind.SUBTITLE, Availability.ENABLED, "he", "Hebrew");
        Option unknown = new Option("unknown-he", RowKind.UNKNOWN, Availability.ENABLED, "he", "Hebrew");
        Option disabled = new Option("disabled-he", RowKind.AUDIO, Availability.DISABLED, "he", "Hebrew");
        List<Option> options = Arrays.asList(EN, subtitle, unknown, disabled);
        none(p.observeMenu(menu(ITEM, 1, "english", options), 100), Reason.NO_HEBREW_AVAILABLE);
        none(p.observeMenu(new MenuSnapshot(ITEM, 20, 2, MenuEvidence.VERIFIED_AUDIO_MENU,
                MenuExtent.PARTIAL, options, "english"), 200), Reason.HEBREW_NOT_YET_VISIBLE);
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(20, "subtitle-he", Origin.SOFTWARE_REMOTE, 210), "subtitles ignored");
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(20, "disabled-he", Origin.PHYSICAL_REMOTE, 210), "disabled ignored");
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(20, "unknown-he", Origin.PHYSICAL_REMOTE, 210), "unknown row ignored");
    }

    private static void physicalChangesRevert() {
        AudioPreferencePolicy p = policy();
        none(p.observeMenu(menu(ITEM, 1, "hebrew"), 100), Reason.TARGET_ALREADY_SELECTED);
        equal(ConfirmationResult.RECORDED,
                p.confirmAudioSelection(20, "english", Origin.PHYSICAL_REMOTE, 150), "physical confirm");
        select(p.observeMenu(menu(ITEM, 2, "english"), 200), "hebrew", Reason.SELECT_HEBREW);
        none(p.observeMenu(menu(ITEM, 3, "hebrew"), 250), Reason.TARGET_ALREADY_SELECTED);
        p.confirmAudioSelection(20, "spanish", Origin.PHYSICAL_REMOTE, 300);
        select(p.observeMenu(menu(ITEM, 4, "spanish"), 350), "hebrew", Reason.SELECT_HEBREW);
        none(p.observeMenu(menu(ITEM, 5, "hebrew"), 400), Reason.TARGET_ALREADY_SELECTED);
    }

    private static void phoneOverrideSurvivesPauseAndPhysicalChanges() {
        AudioPreferencePolicy p = policy();
        p.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        p.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 150);
        none(p.observeMenu(menu(ITEM, 2, "hebrew"), 170), Reason.WAITING_FOR_USER_SELECTION);
        none(p.observeMenu(menu(ITEM, 3, "english"), 200), Reason.TARGET_ALREADY_SELECTED);
        equal(Phase.SOFTWARE_OVERRIDE, p.status().phase, "phone override recorded");
        equal("english", p.status().preferredId, "phone target retained");
        p.beginItem(new Item("com.netflix.ninja", "episode-1"));
        none(p.observeMenu(menu(ITEM, 4, "english"), 60000), Reason.TARGET_ALREADY_SELECTED);
        equal(Phase.SOFTWARE_OVERRIDE, p.status().phase, "pause/resume must preserve override");
        p.confirmAudioSelection(20, "spanish", Origin.PHYSICAL_REMOTE, 60100);
        select(p.observeMenu(menu(ITEM, 5, "spanish"), 60200), "english", Reason.RESTORE_SOFTWARE_OVERRIDE);
        none(p.observeMenu(menu(ITEM, 6, "english"), 60300), Reason.TARGET_ALREADY_SELECTED);
        p.confirmAudioSelection(20, "spanish", Origin.SOFTWARE_REMOTE, 60400);
        none(p.observeMenu(menu(ITEM, 7, "spanish"), 60500), Reason.TARGET_ALREADY_SELECTED);
        equal("spanish", p.status().preferredId, "second phone choice replaces first");
    }

    private static void assistantOverrideAndNextItem() {
        AudioPreferencePolicy p = policy();
        p.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        p.confirmAudioSelection(20, "english", Origin.ASSISTANT, 150);
        none(p.observeMenu(menu(ITEM, 2, "english"), 200), Reason.TARGET_ALREADY_SELECTED);
        equal(Phase.SOFTWARE_OVERRIDE, p.status().phase, "assistant override");
        Item next = new Item("com.netflix.ninja", "episode-2");
        p.beginItem(next);
        select(p.observeMenu(menu(next, 1, "english"), 300), "hebrew", Reason.SELECT_HEBREW);
        equal(Phase.HEBREW_DEFAULT, p.status().phase, "new actual item restores default");
        p.endItem();
        none(p.observeMenu(menu(next, 2, "hebrew"), 400), Reason.NO_ITEM);
    }

    private static void unknownAndStaleAttributionPreserved() {
        AudioPreferencePolicy p = policy();
        p.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        none(p.observeMenu(menu(ITEM, 2, "english"), 200), Reason.UNRESOLVED_ATTRIBUTION);
        equal(Phase.UNRESOLVED_ATTRIBUTION, p.status().phase, "no origin guessed");
        none(p.observeMenu(menu(ITEM, 3, "english"), 10000), Reason.UNRESOLVED_ATTRIBUTION);

        AudioPreferencePolicy stale = policy();
        stale.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        stale.confirmAudioSelection(20, "english", Origin.PHYSICAL_REMOTE, 150);
        none(stale.observeMenu(menu(ITEM, 2, "english"), 3151), Reason.UNRESOLVED_ATTRIBUTION);

        AudioPreferencePolicy unknown = policy();
        unknown.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        unknown.confirmAudioSelection(20, "english", Origin.UNKNOWN, 150);
        none(unknown.observeMenu(menu(ITEM, 2, "english"), 200), Reason.UNRESOLVED_ATTRIBUTION);
    }

    private static void mixedSourcesPreserved() {
        AudioPreferencePolicy p = policy();
        p.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        p.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 150);
        p.confirmAudioSelection(20, "spanish", Origin.PHYSICAL_REMOTE, 160);
        none(p.observeMenu(menu(ITEM, 2, "spanish"), 200), Reason.UNRESOLVED_ATTRIBUTION);
    }

    private static void queuedAutomaticActionCancelled() {
        AudioPreferencePolicy p = policy();
        Decision queued = p.observeMenu(menu(ITEM, 1, "english"), 100);
        truth(p.isCurrent(queued), "initial queued action current");
        p.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 110);
        truth(!p.isCurrent(queued), "phone cancels queued automatic action");
        none(p.observeMenu(menu(ITEM, 2, "english"), 150), Reason.TARGET_ALREADY_SELECTED);
        equal(Phase.SOFTWARE_OVERRIDE, p.status().phase, "choosing current English still overrides default");
    }

    private static void boundedRetries() {
        AudioPreferencePolicy p = policy();
        select(p.observeMenu(menu(ITEM, 1, "english"), 100), "hebrew", Reason.SELECT_HEBREW);
        none(p.observeMenu(menu(ITEM, 2, "english"), 3100), Reason.WAITING_FOR_AUTOMATIC_SELECTION);
        select(p.observeMenu(menu(ITEM, 3, "english"), 3101), "hebrew", Reason.SELECT_HEBREW);
        none(p.observeMenu(menu(ITEM, 4, "english"), 6102), Reason.ATTEMPTS_EXHAUSTED);
        none(p.observeMenu(menu(ITEM, 5, "english"), 100000), Reason.ATTEMPTS_EXHAUSTED);
        equal(2, p.status().automaticAttempts, "bounded attempts");
        Item next = new Item(ITEM.packageName, "episode-2");
        p.beginItem(next);
        select(p.observeMenu(menu(next, 1, "english"), 100100), "hebrew", Reason.SELECT_HEBREW);
    }

    private static void invalidMenusAndConfirmations() {
        AudioPreferencePolicy p = policy();
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 50), "no verified snapshot");
        none(p.observeMenu(new MenuSnapshot(ITEM, 20, 1, MenuEvidence.UNVERIFIED, MenuExtent.COMPLETE,
                OPTIONS, "english"), 100), Reason.UNVERIFIED_MENU);
        none(p.observeMenu(menu(ITEM, 1, ""), 100), Reason.NO_SELECTED_AUDIO);
        none(p.observeMenu(menu(new Item("com.amazon.amazonvideo.livingroom", "episode-1"), 2, "english"), 200),
                Reason.DIFFERENT_ITEM);
        p.observeMenu(menu(ITEM, 2, "hebrew"), 200);
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(999, "english", Origin.SOFTWARE_REMOTE, 250), "wrong window");
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(20, "no-such-row", Origin.SOFTWARE_REMOTE, 250), "wrong row");
        equal(ConfirmationResult.IGNORED,
                p.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, -1), "invalid time");
    }

    private static void menuIdentityAndFreshEvidence() {
        AudioPreferencePolicy p = policy();
        p.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        p.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 150);
        none(p.observeMenu(menu(ITEM, 1, "english"), 200), Reason.STALE_SNAPSHOT);
        none(p.observeMenu(new MenuSnapshot(ITEM, 21, 2, MenuEvidence.VERIFIED_AUDIO_MENU,
                MenuExtent.COMPLETE, OPTIONS, "english"), 250), Reason.UNRESOLVED_ATTRIBUTION);

        AudioPreferencePolicy missing = policy();
        missing.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        missing.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 150);
        missing.observeMenu(menu(ITEM, 2, "english"), 200);
        // Menus that hide the chosen track cannot justify selecting a different track.
        none(missing.observeMenu(menu(ITEM, 3, "", Arrays.asList(HE, HE_AD)), 250), Reason.NO_SELECTED_AUDIO);
        equal(Phase.SOFTWARE_OVERRIDE, missing.status().phase, "missing target does not clear override");
    }

    private static void boundedInputData() {
        try {
            menu(ITEM, 1, "english", Arrays.asList(EN, EN));
            throw new AssertionError("duplicate IDs accepted");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }
        List<Option> tooMany = new ArrayList<Option>();
        for (int n = 0; n <= AudioPreferencePolicy.MAX_MENU_ROWS; n++) {
            tooMany.add(audio("row-" + n, "en", "English"));
        }
        try {
            menu(ITEM, 1, "row-0", tooMany);
            throw new AssertionError("unbounded menu accepted");
        } catch (IllegalArgumentException expected) {
            assertions++;
        }
    }

    private static void staleWindowsCancelActionsWithoutResettingOverrides() {
        AudioPreferencePolicy p = policy();
        Decision queued = p.observeMenu(menu(ITEM, 1, "english"), 100);
        p.invalidateMenu();
        truth(!p.isCurrent(queued), "window close cancels queued action");
        equal(Phase.HEBREW_DEFAULT, p.status().phase, "window loss does not end item");
        none(p.observeMenu(menu(ITEM, 1, "english"), 200), Reason.STALE_SNAPSHOT);

        AudioPreferencePolicy changed = policy();
        Decision beforeChange = changed.observeMenu(menu(ITEM, 1, "english"), 100);
        changed.observeMenu(menu(new Item("com.disney.disneyplus", "another-title"), 2, "english"), 200);
        truth(!changed.isCurrent(beforeChange), "another app invalidates queued action");

        AudioPreferencePolicy incomplete = policy();
        Decision beforeIncomplete = incomplete.observeMenu(menu(ITEM, 1, "english"), 100);
        incomplete.observeMenu(menu(ITEM, 2, ""), 200);
        truth(!incomplete.isCurrent(beforeIncomplete), "missing selected evidence invalidates queued action");

        AudioPreferencePolicy override = policy();
        override.observeMenu(menu(ITEM, 1, "hebrew"), 100);
        override.confirmAudioSelection(20, "english", Origin.SOFTWARE_REMOTE, 150);
        override.observeMenu(menu(ITEM, 2, "english"), 200);
        override.invalidateMenu();
        equal(Phase.SOFTWARE_OVERRIDE, override.status().phase, "menu close preserves phone override");
        none(override.observeMenu(menu(ITEM, 3, "english"), 300), Reason.TARGET_ALREADY_SELECTED);
    }

    private static AudioPreferencePolicy policy() {
        AudioPreferencePolicy policy = new AudioPreferencePolicy();
        policy.beginItem(ITEM);
        return policy;
    }

    private static Option audio(String id, String tag, String label) {
        return new Option(id, RowKind.AUDIO, Availability.ENABLED, tag, label);
    }

    private static MenuSnapshot menu(Item item, long sequence, String selected) {
        return menu(item, sequence, selected, OPTIONS);
    }

    private static MenuSnapshot menu(Item item, long sequence, String selected, List<Option> options) {
        return new MenuSnapshot(item, 20, sequence, MenuEvidence.VERIFIED_AUDIO_MENU,
                MenuExtent.COMPLETE, options, selected);
    }

    private static void select(Decision decision, String target, Reason reason) {
        equal(Action.SELECT_AUDIO, decision.action, "action");
        equal(target, decision.targetId, "target");
        equal(reason, decision.reason, "reason");
    }

    private static void none(Decision decision, Reason reason) {
        equal(Action.NONE, decision.action, "no action");
        equal(reason, decision.reason, "reason");
    }

    private static void truth(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}

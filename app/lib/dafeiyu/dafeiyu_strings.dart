// Generated from Dafeiyu ARB delta so v1.0.16 app/lib/l10n stays 0-diff.
import 'package:flutter/widgets.dart';

import '../l10n/l10n.dart';

abstract class DafeiyuStrings {
  const DafeiyuStrings();

  static const DafeiyuStrings en = _DafeiyuStringsEn();
  static const DafeiyuStrings zh = _DafeiyuStringsZh();

  static DafeiyuStrings of(BuildContext context) {
    final locale = Localizations.maybeLocaleOf(context) ?? WidgetsBinding.instance.platformDispatcher.locale;
    return locale.languageCode == 'zh' ? zh : en;
  }

  static DafeiyuStrings fromL10n(L10n l10n) =>
      l10n.localeName.startsWith('zh') ? zh : en;

  static DafeiyuStrings get current => fromL10n(appL10n);

  String get boutVoiceDone;
  String get boutVoiceFallbackIn;
  String get boutVoiceFallbackOut;
  String get boutVoiceHeardLabel;
  String get boutVoiceIntro;
  String get boutVoiceJudging;
  String get boutVoiceListening;
  String get boutVoiceNoBout;
  String get boutVoiceSayLabel;
  String get boutVoiceSpeaking;
  String get boutVoiceStart;
  String get boutVoiceStop;
  String get boutVoiceSubmit;
  String get boutVoiceWaitAsk;
  String get boutVoiceWaitText;
  String get catalogBoutVoice;
  String get catalogBoutVoiceSummary;
  String interceptActive(String package);
  String get interceptAddTarget;
  String get interceptAddTargetHint;
  String get interceptAddTargetInvalid;
  String get interceptCooldown;
  String get interceptDailyMax;
  String get interceptDailyMaxOff;
  String get interceptEnabled;
  String get interceptEnabledHint;
  String get interceptLedger;
  String get interceptLedgerBlocked;
  String get interceptLedgerExited;
  String get interceptLedgerPaused;
  String get interceptLedgerReleased;
  String get interceptLedgerTriggers;
  String get interceptMode;
  String get interceptNoTargets;
  String get interceptOutcomeBlocked;
  String get interceptOutcomeExited;
  String get interceptOutcomeNotShown;
  String get interceptOutcomePaused;
  String get interceptOutcomeReleased;
  String get interceptPause;
  String get interceptPauseHint;
  String get interceptPauseNow;
  String interceptPausedUntil(String time);
  String get interceptReceipts;
  String get interceptReceiptsEmpty;
  String interceptRecoveryIssue(String error);
  String get interceptRefresh;
  String get interceptRelease;
  String get interceptRemoveTarget;
  String get interceptResume;
  String interceptStorageError(String error);
  String get interceptSubtitle;
  String get interceptTargets;
  String get interceptTargetsHint;
  String get interceptTitle;
  String get interceptTraining;
  String get interceptUnavailable;
  String get interceptUnavailableHint;
  String get interceptWhitelist;
  String get interceptWhitelistEmpty;
  String get interceptWhitelistHint;
  String get loopA11yNeeded;
  String get loopA11yReady;
  String get loopA11yWaiting;
  String get loopClear;
  String get loopCooldown;
  String get loopCooldownHint;
  String loopCounters(int events, int readings, String app);
  String get loopDailyCap;
  String get loopDailyCapHint;
  String get loopDailyCapOff;
  String loopDailyCapValue(int used, int cap);
  String get loopDiagnostics;
  String get loopDiagnosticsClear;
  String loopDiagnosticsLastRead(String time);
  String loopDiagnosticsSummary(int events, int readings, int faults);
  String get loopEnabled;
  String get loopEnabledHint;
  String get loopEntrySubtitle;
  String get loopEntryTitle;
  String get loopExport;
  String loopExportDone(int count);
  String get loopFaultAttach;
  String get loopFaultEvent;
  String get loopFaultRead;
  String get loopFaultSurface;
  String get loopFaultVerify;
  String get loopNoApps;
  String get loopPageSubtitle;
  String get loopPickApp;
  String get loopQuietHours;
  String get loopQuietHoursHint;
  String get loopQuietHoursOff;
  String loopQuietHoursValue(String start, String end);
  String get loopReceipts;
  String get loopReceiptsEmpty;
  String loopRecoveredFromBackup(String file);
  String get loopRefresh;
  String get loopResultAbandoned;
  String get loopResultBack;
  String get loopResultDismissed;
  String get loopResultError;
  String get loopResultOpen;
  String get loopResultReturned;
  String get loopResultSuppressed;
  String get loopResultTimeout;
  String get loopResultUnavailable;
  String loopSeconds(int seconds);
  String loopStorageError(String error);
  String get loopSurfaceNotification;
  String get loopTargetApp;
  String get loopTargetNone;
  String get loopTimeout;
  String get loopTimeoutHint;
  String loopVisitShort(String id);
  String get mountAccept;
  String get mountAccepted;
  String get mountActions;
  String get mountAddGiven;
  String get mountAddStep;
  String get mountAdded;
  String get mountCardOpen;
  String get mountClose;
  String get mountCloseHint;
  String get mountClosed;
  String get mountDrop;
  String get mountDropped;
  String get mountEmptyHint;
  String get mountFailed;
  String get mountFromGivens;
  String get mountFromSource;
  String get mountGivenPrompt;
  String get mountGivens;
  String get mountGivensEmpty;
  String get mountGivensFull;
  String get mountGoal;
  String get mountGoalEmpty;
  String get mountGoalPrompt;
  String get mountHandWritten;
  String get mountLoading;
  String get mountMiniAsk;
  String get mountMiniCounts;
  String get mountNeedsSource;
  String get mountNoCard;
  String get mountNotUserText;
  String get mountOpen;
  String get mountOpenHint;
  String get mountOpened;
  String get mountOtherCards;
  String get mountPark;
  String get mountParkHint;
  String get mountParkNeedsWhy;
  String get mountParkPrompt;
  String get mountParked;
  String get mountParkedWhy;
  String get mountPending;
  String get mountPendingGiven;
  String get mountPendingStep;
  String get mountRefresh;
  String get mountResume;
  String get mountResumeHint;
  String get mountResumed;
  String get mountStatusClosed;
  String get mountStatusNone;
  String get mountStatusOpen;
  String get mountStatusParked;
  String get mountStepNeedsGiven;
  String get mountStepPick;
  String get mountStepPrompt;
  String get mountSteps;
  String get mountStepsEmpty;
  String get mountStepsFull;
  String get mountSubtitle;
  String get mountTitle;
  String get phoneDisclosureTriggerBody;
  String get phoneDisclosureTriggerTitle;
  String get phoneDndAdbReady;
  String get phoneDndAdbStatus;
  String get phoneDndEnable;
  String get phoneDndPairOnce;
  String get phoneDndShizukuReady;
  String get phoneDndShizukuStatus;
  String get phoneDndUseShizuku;
  String get phoneDndUsingAdb;
  String get phoneDndUsingShizuku;
  String get phoneGeminiEmptyTopic;
  String get phoneGeminiEnd;
  String get phoneGeminiEnded;
  String get phoneGeminiFailed;
  String get phoneGeminiHint;
  String get phoneGeminiInterrogate;
  String get phoneGeminiMinutes;
  String get phoneGeminiMode;
  String get phoneGeminiRunning;
  String get phoneGeminiStart;
  String get phoneGeminiStarted;
  String get phoneGeminiStudy;
  String get phoneGeminiThink;
  String get phoneGeminiTitle;
  String get phoneGeminiTopic;
  String get phoneWallHint;
  String get phoneWallLocked;
  String get phoneWallQuota;
  String get phoneWallTitle;
  String get phoneWallTodayChanged;
  String get phoneWallTodaySame;
  String get phoneWallUnlocked;
  String proactiveActiveStage(String stage);
  String get proactiveDaily;
  String get proactiveDailyHint;
  String proactiveDailyValue(int count);
  String get proactiveEnabled;
  String get proactiveEnabledHint;
  String get proactiveGap;
  String get proactiveGapHint;
  String get proactiveGapMax;
  String get proactiveGapMaxHint;
  String proactiveGapValue(int min, int max);
  String get proactiveGateFree;
  String proactiveGateHold(String reason);
  String get proactiveHoldA11y;
  String get proactiveHoldDshPrompt;
  String get proactiveHoldDshTask;
  String get proactiveHoldLocked;
  String get proactiveHoldProtected;
  String get proactiveHoldScreenOff;
  String get proactiveMarkAccepted;
  String get proactiveMarkDelivered;
  String get proactiveMarkGenerated;
  String get proactiveMarkResponded;
  String get proactiveMarkShown;
  String proactiveMinutes(int minutes);
  String get proactiveOutcomeAcked;
  String get proactiveOutcomeDismissed;
  String get proactiveOutcomeIgnoredSeen;
  String get proactiveOutcomeNotDelivered;
  String get proactiveOutcomeOpen;
  String get proactiveOutcomeResponded;
  String get proactiveOutcomeUnconfirmed;
  String get proactivePickSession;
  String proactiveQueued(int count);
  String get proactiveQuiet;
  String get proactiveQuietHint;
  String proactiveQuietValue(String start, String end);
  String get proactiveReceipts;
  String get proactiveReceiptsEmpty;
  String proactiveRecoveryIssue(String issue);
  String get proactiveSession;
  String proactiveSessionChosen(String id);
  String get proactiveSessionEmpty;
  String get proactiveSessionHint;
  String get proactiveSessionIdle;
  String get proactiveSessionNone;
  String get proactiveSessionNoneHint;
  String get proactiveSessionRunning;
  String proactiveSessionUnavailable(String error);
  String get proactiveState;
  String get proactiveSubtitle;
  String get proactiveTitle;
  String get proactiveTransportMissing;
  String get proactiveTransportReady;
  String proactiveWakeMode(String mode);
  String get proactiveWakeRestricted;
  String get voiceOutCompanion;
  String get voiceOutEnable;
  String get voiceOutEnableHint;
  String get voiceOutFooter;
  String get voiceOutKey;
  String get voiceOutKeySet;
  String get voiceOutKeyUnset;
  String get voiceOutOfficial;
  String get voiceOutPreview;
  String get voiceOutPreviewFailed;
  String get voiceOutPreviewText;
  String get voiceOutSection;
  String get voiceOutService;
  String get voiceOutStrategist;
  String get voiceOutVoiceFollow;
  String get wallActionAppeal;
  String get wallActionSubmit;
  String get wallAppealEvidence;
  String get wallAppealEvidenceHint;
  String get wallAppealLeft;
  String get wallAppealNoEvidence;
  String get wallAppealNoTarget;
  String get wallAppealNone;
  String get wallAppealSend;
  String get wallAppealTarget;
  String get wallBubbleRule;
  String get wallBubbleSubmit;
  String get wallBubbleVerdict;
  String get wallRefresh;
  String get wallStatusLocked;
  String get wallStatusOpen;
  String get wallStatusUnknown;
  String get wallStatusUntil;
  String get wallStatusWhy;
  String get wallThreadEmpty;
  String get wallThreadFailed;
  String get wallTitle;
}

class _DafeiyuStringsEn extends DafeiyuStrings {
  const _DafeiyuStringsEn();

  @override
  String get boutVoiceDone => 'This bout is over.';
  @override
  String get boutVoiceFallbackIn => 'Didn’t catch that one, so type it instead.';
  @override
  String get boutVoiceFallbackOut => 'No voice available, so this line is shown as text.';
  @override
  String get boutVoiceHeardLabel => 'You said';
  @override
  String get boutVoiceIntro => 'The official says one line, you answer out loud. Being read to doesn’t count against your time; the clock starts when it finishes.';
  @override
  String get boutVoiceJudging => 'Judging that answer';
  @override
  String get boutVoiceListening => 'Listening';
  @override
  String get boutVoiceNoBout => 'No bout is running. Open Bouts and start one, then come back and press Start.';
  @override
  String get boutVoiceSayLabel => 'The official';
  @override
  String get boutVoiceSpeaking => 'The official is speaking';
  @override
  String get boutVoiceStart => 'Start';
  @override
  String get boutVoiceStop => 'Stop';
  @override
  String get boutVoiceSubmit => 'Send this';
  @override
  String get boutVoiceWaitAsk => 'Waiting for the next line';
  @override
  String get boutVoiceWaitText => 'Didn’t hear that. Type it instead, or stop.';
  @override
  String get catalogBoutVoice => 'Bouts · Voice';
  @override
  String get catalogBoutVoiceSummary => 'The official reads, you answer aloud';
  @override
  String interceptActive(String package) => 'Blocking now: $package';
  @override
  String get interceptAddTarget => 'Add entertainment app';
  @override
  String get interceptAddTargetHint => 'An Android package name, e.g. tv.danmaku.bili';
  @override
  String get interceptAddTargetInvalid => 'A package name cannot be empty or contain spaces';
  @override
  String get interceptCooldown => 'Cooldown (minutes)';
  @override
  String get interceptDailyMax => 'Daily limit';
  @override
  String get interceptDailyMaxOff => 'No limit';
  @override
  String get interceptEnabled => 'Turn on entertainment lock';
  @override
  String get interceptEnabledHint => 'When on, opening an app below triggers the lock screen; cooldown prevents repeated nagging.';
  @override
  String get interceptLedger => 'Block ledger';
  @override
  String get interceptLedgerBlocked => 'Actually blocked';
  @override
  String get interceptLedgerExited => 'Left';
  @override
  String get interceptLedgerPaused => 'Paused';
  @override
  String get interceptLedgerReleased => 'Let through';
  @override
  String get interceptLedgerTriggers => 'Triggered';
  @override
  String get interceptMode => 'Parameters';
  @override
  String get interceptNoTargets => 'No entertainment apps added yet';
  @override
  String get interceptOutcomeBlocked => 'Blocked, no ending yet';
  @override
  String get interceptOutcomeExited => 'Emergency exit';
  @override
  String get interceptOutcomeNotShown => 'Never shown';
  @override
  String get interceptOutcomePaused => 'Paused';
  @override
  String get interceptOutcomeReleased => 'Let through';
  @override
  String get interceptPause => 'Pause and resume';
  @override
  String get interceptPauseHint => 'Nothing is blocked while paused; it resumes on its own.';
  @override
  String get interceptPauseNow => 'Pause for 30 minutes';
  @override
  String interceptPausedUntil(String time) => 'Paused until $time';
  @override
  String get interceptReceipts => 'Recent receipts';
  @override
  String get interceptReceiptsEmpty => 'No receipts yet';
  @override
  String interceptRecoveryIssue(String error) => 'Unreadable state at start-up: $error';
  @override
  String get interceptRefresh => 'Refresh';
  @override
  String get interceptRelease => 'Pass duration (minutes)';
  @override
  String get interceptRemoveTarget => 'Remove';
  @override
  String get interceptResume => 'Resume blocking';
  @override
  String interceptStorageError(String error) => 'Could not write state: $error';
  @override
  String get interceptSubtitle => 'Locks selected entertainment apps on launch with Strict, Focus-linked, or Calm-training discipline so accidental bypasses cannot stick.';
  @override
  String get interceptTargets => 'Controlled entertainment apps';
  @override
  String get interceptTargetsHint => 'Pick popular video/game presets or installed apps on this phone; removing one stops locking it immediately.';
  @override
  String get interceptTitle => 'Entertainment lock';
  @override
  String get interceptTraining => 'Drill length (seconds)';
  @override
  String get interceptUnavailable => 'This build has no block kernel';
  @override
  String get interceptUnavailableHint => 'Only the full phone build has it. Desktop and this build have no dsh/intercept channel.';
  @override
  String get interceptWhitelist => 'Whitelist';
  @override
  String get interceptWhitelistEmpty => 'The whitelist is empty';
  @override
  String get interceptWhitelistHint => 'A whitelisted package is never blocked, even if it is a target.';
  @override
  String get loopA11yNeeded => 'Turn on DSH\'s accessibility service in system settings first';
  @override
  String get loopA11yReady => 'Accessibility service connected';
  @override
  String get loopA11yWaiting => 'Accessibility is on, waiting for the service to connect';
  @override
  String get loopClear => 'Clear receipts';
  @override
  String get loopCooldown => 'Cooldown';
  @override
  String get loopCooldownHint => 'Minimum time between two pages; tap to change';
  @override
  String loopCounters(int events, int readings, String app) => '$events window events, $readings checks · last foreground $app';
  @override
  String get loopDailyCap => 'Daily limit';
  @override
  String get loopDailyCapHint => 'At most this many test pages a day; resets after midnight';
  @override
  String get loopDailyCapOff => 'No limit';
  @override
  String loopDailyCapValue(int used, int cap) => '$used/$cap today';
  @override
  String get loopDiagnostics => 'Accessibility diagnostics';
  @override
  String get loopDiagnosticsClear => 'Clear diagnostics';
  @override
  String loopDiagnosticsLastRead(String time) => 'Last successful foreground read: $time';
  @override
  String loopDiagnosticsSummary(int events, int readings, int faults) => '$events window events, $readings foreground checks, $faults failures';
  @override
  String get loopEnabled => 'Turn on trigger test';
  @override
  String get loopEnabledHint => 'Never shows while DSH is operating the phone or a confirmation is waiting';
  @override
  String get loopEntrySubtitle => 'Shows a test page when you open a chosen app (experimental)';
  @override
  String get loopEntryTitle => 'Trigger test';
  @override
  String get loopExport => 'Export receipts (JSON to clipboard)';
  @override
  String loopExportDone(int count) => 'Copied $count receipts to the clipboard';
  @override
  String get loopFaultAttach => 'Connect failed';
  @override
  String get loopFaultEvent => 'Window event failed';
  @override
  String get loopFaultRead => 'Foreground read failed';
  @override
  String get loopFaultSurface => 'Showing the question failed';
  @override
  String get loopFaultVerify => 'Trigger check failed';
  @override
  String get loopNoApps => 'No apps found';
  @override
  String get loopPageSubtitle => 'The first closed loop: open the test app → a test page appears → tap Back → a receipt is saved. Only the foreground app\'s package name is read, never page content.';
  @override
  String get loopPickApp => 'Choose the test app';
  @override
  String get loopQuietHours => 'Quiet hours';
  @override
  String get loopQuietHoursHint => 'No test page during this window; may cross midnight. Tap to change.';
  @override
  String get loopQuietHoursOff => 'Off';
  @override
  String loopQuietHoursValue(String start, String end) => '$start–$end';
  @override
  String get loopReceipts => 'Receipts';
  @override
  String get loopReceiptsEmpty => 'No receipts yet';
  @override
  String loopRecoveredFromBackup(String file) => 'The receipt file was damaged; loaded the backup ($file)';
  @override
  String get loopRefresh => 'Refresh';
  @override
  String get loopResultAbandoned => 'Closed after restart';
  @override
  String get loopResultBack => 'Pressed system back';
  @override
  String get loopResultDismissed => 'Left the page';
  @override
  String get loopResultError => 'Error';
  @override
  String get loopResultOpen => 'In progress';
  @override
  String get loopResultReturned => 'Tapped Back';
  @override
  String get loopResultSuppressed => 'Not triggered';
  @override
  String get loopResultTimeout => 'No answer in time';
  @override
  String get loopResultUnavailable => 'Page couldn\'t show';
  @override
  String loopSeconds(int seconds) => '$seconds s';
  @override
  String loopStorageError(String error) => 'Couldn\'t save receipts: $error';
  @override
  String get loopSurfaceNotification => 'via notification';
  @override
  String get loopTargetApp => 'Test app';
  @override
  String get loopTargetNone => 'None';
  @override
  String get loopTimeout => 'Time to answer';
  @override
  String get loopTimeoutHint => 'After this it counts as "no answer in time"; tap to change';
  @override
  String loopVisitShort(String id) => 'visit $id';
  @override
  String get mountAccept => 'Agree, add it';
  @override
  String get mountAccepted => 'Added';
  @override
  String get mountActions => 'Next';
  @override
  String get mountAddGiven => 'Add a given';
  @override
  String get mountAddStep => 'Add a conclusion';
  @override
  String get mountAdded => 'Added';
  @override
  String get mountCardOpen => 'A card is already open. Park or close it first.';
  @override
  String get mountClose => 'Close this card';
  @override
  String get mountCloseHint => 'Closed cards are read-only: kept, but no longer injected.';
  @override
  String get mountClosed => 'Closed';
  @override
  String get mountDrop => 'Not this one';
  @override
  String get mountDropped => 'Dropped';
  @override
  String get mountEmptyHint => 'Open one when you start working a problem, so you never have to ask what you were solving.';
  @override
  String get mountFailed => 'Cannot read the mount card, try again later';
  @override
  String get mountFromGivens => 'Follows from given {from}';
  @override
  String get mountFromSource => 'From ledger line {src}';
  @override
  String get mountGivenPrompt => 'A fact he has already confirmed';
  @override
  String get mountGivens => 'Givens';
  @override
  String get mountGivensEmpty => 'No givens yet. Add the facts he has already confirmed, one at a time.';
  @override
  String get mountGivensFull => 'Givens are full (5 max). Remove one first.';
  @override
  String get mountGoal => 'Goal';
  @override
  String get mountGoalEmpty => 'A card needs a goal first.';
  @override
  String get mountGoalPrompt => 'What are you solving (his own words if possible)';
  @override
  String get mountHandWritten => 'Written by him';
  @override
  String get mountLoading => 'Reading the mount card…';
  @override
  String get mountMiniAsk => 'That does not match the card: keep this one, or park it?';
  @override
  String get mountMiniCounts => 'Givens {givens}/{maxGivens} · at step {steps}/{maxSteps}';
  @override
  String get mountNeedsSource => 'A proposal needs a ledger line. No source, no proposal.';
  @override
  String get mountNoCard => 'No card is open right now.';
  @override
  String get mountNotUserText => 'Only his own words go on the card, never a summary.';
  @override
  String get mountOpen => 'Open a new card';
  @override
  String get mountOpenHint => 'One line on what this is solving. His own words are best.';
  @override
  String get mountOpened => 'Card opened';
  @override
  String get mountOtherCards => 'Other cards';
  @override
  String get mountPark => 'Park it for now';
  @override
  String get mountParkHint => 'Say where it stops, so you can pick it up later.';
  @override
  String get mountParkNeedsWhy => 'Parking needs a line saying where it stops.';
  @override
  String get mountParkPrompt => 'Where it stops: how far you got, where to resume';
  @override
  String get mountParked => 'Parked';
  @override
  String get mountParkedWhy => 'Parked at: {why}';
  @override
  String get mountPending => 'She proposes adding one';
  @override
  String get mountPendingGiven => 'Given';
  @override
  String get mountPendingStep => 'Conclusion: after you agree, pick which givens it follows from';
  @override
  String get mountRefresh => 'Refresh';
  @override
  String get mountResume => 'Pick this one back up';
  @override
  String get mountResumeHint => 'Back to where it stopped; the goal and the givens are still there.';
  @override
  String get mountResumed => 'Picking it up again';
  @override
  String get mountStatusClosed => 'Card closed';
  @override
  String get mountStatusNone => 'No card yet';
  @override
  String get mountStatusOpen => 'Card open';
  @override
  String get mountStatusParked => 'Card parked';
  @override
  String get mountStepNeedsGiven => 'No givens on the card, so there is nothing to derive from.';
  @override
  String get mountStepPick => 'Which givens does this conclusion follow from';
  @override
  String get mountStepPrompt => 'Where did you get to (one line)';
  @override
  String get mountSteps => 'Conclusions';
  @override
  String get mountStepsEmpty => 'No conclusions yet. Each one must say which givens it follows from.';
  @override
  String get mountStepsFull => 'Conclusions are full (5 max). Remove one first.';
  @override
  String get mountSubtitle => 'Do not lose the thread halfway. Goal, givens and where you got to — in his own words.';
  @override
  String get mountTitle => 'Mount card';
  @override
  String get phoneDisclosureTriggerBody => 'Only after you switch on the trigger test or proactive chat does DSH read the name (package) of the foreground app: the trigger test uses it to tell whether you opened the test app, and both use it to stay off Settings, permission and install screens. No page content, no screen recording, no upload. You can switch them off at any time.';
  @override
  String get phoneDisclosureTriggerTitle => 'Trigger test and proactive chat (off by default)';
  @override
  String get phoneDndAdbReady => 'Direct ADB · Ready';
  @override
  String get phoneDndAdbStatus => 'Direct ADB · {reason}';
  @override
  String get phoneDndEnable => 'Enable non-disruptive mode';
  @override
  String get phoneDndPairOnce => 'Pair once (no extra app)';
  @override
  String get phoneDndShizukuReady => 'Shizuku · Ready';
  @override
  String get phoneDndShizukuStatus => 'Shizuku · {reason}';
  @override
  String get phoneDndUseShizuku => 'Use Shizuku';
  @override
  String get phoneDndUsingAdb => 'Using Direct ADB';
  @override
  String get phoneDndUsingShizuku => 'Using Shizuku';
  @override
  String get phoneGeminiEmptyTopic => 'Session (no topic)';
  @override
  String get phoneGeminiEnd => 'End the session';
  @override
  String get phoneGeminiEnded => 'Session over; locks are back';
  @override
  String get phoneGeminiFailed => 'No answer from Da Fei Yu. Try again.';
  @override
  String get phoneGeminiHint => 'Only Gemini stays open; other apps lock the moment they open. Calls, messages, maps and payment stay available.';
  @override
  String get phoneGeminiInterrogate => 'Interrogate slacking';
  @override
  String get phoneGeminiMinutes => 'Length';
  @override
  String get phoneGeminiMode => 'How to help';
  @override
  String get phoneGeminiRunning => 'Running: {topic} · {minutes} min left · Gemini only';
  @override
  String get phoneGeminiStart => 'Start a session';
  @override
  String get phoneGeminiStarted => 'Session started: Gemini only';
  @override
  String get phoneGeminiStudy => 'Study coach';
  @override
  String get phoneGeminiThink => 'Think together';
  @override
  String get phoneGeminiTitle => 'Gemini session';
  @override
  String get phoneGeminiTopic => 'What is this session for? (chapter 3, the report…)';
  @override
  String get phoneWallHint => 'Locks and breaks follow the rules he set while clear-headed; chat cannot change them. For more time, ask Jinglan in the window after the evening review.';
  @override
  String get phoneWallLocked => 'Locked now: {why}';
  @override
  String get phoneWallQuota => '{days} relaxed days left this week';
  @override
  String get phoneWallTitle => 'The wall (rules)';
  @override
  String get phoneWallTodayChanged => 'Rules changed today';
  @override
  String get phoneWallTodaySame => 'No rule changes today';
  @override
  String get phoneWallUnlocked => 'Nothing locked now';
  @override
  String proactiveActiveStage(String stage) => 'Working on: $stage';
  @override
  String get proactiveDaily => 'Daily limit';
  @override
  String get proactiveDailyHint => 'At most this many a day, recounted after midnight; undelivered ones do not count';
  @override
  String proactiveDailyValue(int count) => '$count times';
  @override
  String get proactiveEnabled => 'Turn on proactive messages';
  @override
  String get proactiveEnabledHint => 'Off by default. When on, it finds you at random intervals; it steps back while DSH is operating the phone, while a confirmation is waiting, when the screen is locked, and during quiet hours.';
  @override
  String get proactiveGap => 'Shortest gap';
  @override
  String get proactiveGapHint => 'At least this long between two messages; the real gap is random between this and the longest gap';
  @override
  String get proactiveGapMax => 'Longest gap';
  @override
  String get proactiveGapMaxHint => 'Upper bound of the random gap; tap to change';
  @override
  String proactiveGapValue(int min, int max) => '$min–$max min';
  @override
  String get proactiveGateFree => 'Clear: no DSH task running and no protected page in front';
  @override
  String proactiveGateHold(String reason) => 'Holding back: $reason';
  @override
  String get proactiveHoldA11y => 'accessibility is not connected';
  @override
  String get proactiveHoldDshPrompt => 'A confirmation is waiting for you';
  @override
  String get proactiveHoldDshTask => 'DSH is operating the phone';
  @override
  String get proactiveHoldLocked => 'the screen is locked';
  @override
  String get proactiveHoldProtected => 'a permission or security page is in front';
  @override
  String get proactiveHoldScreenOff => 'the screen is off';
  @override
  String get proactiveMarkAccepted => 'handed to the agent';
  @override
  String get proactiveMarkDelivered => 'sent out';
  @override
  String get proactiveMarkGenerated => 'model answered';
  @override
  String get proactiveMarkResponded => 'you responded';
  @override
  String get proactiveMarkShown => 'shown on screen';
  @override
  String proactiveMinutes(int minutes) => '$minutes min';
  @override
  String get proactiveOutcomeAcked => 'You said you saw it';
  @override
  String get proactiveOutcomeDismissed => 'You dismissed it';
  @override
  String get proactiveOutcomeIgnoredSeen => 'Shown, not answered';
  @override
  String get proactiveOutcomeNotDelivered => 'Never delivered';
  @override
  String get proactiveOutcomeOpen => 'In progress';
  @override
  String get proactiveOutcomeResponded => 'You responded';
  @override
  String get proactiveOutcomeUnconfirmed => 'Notification sent; whether you saw it is unknown';
  @override
  String get proactivePickSession => 'Pick a session';
  @override
  String proactiveQueued(int count) => 'Queued: $count';
  @override
  String get proactiveQuiet => 'Quiet hours';
  @override
  String get proactiveQuietHint => 'No proactive message during this window; may cross midnight. Tap to change.';
  @override
  String proactiveQuietValue(String start, String end) => '$start–$end';
  @override
  String get proactiveReceipts => 'Receipts';
  @override
  String get proactiveReceiptsEmpty => 'Nothing yet';
  @override
  String proactiveRecoveryIssue(String issue) => 'The last state could not be read: $issue';
  @override
  String get proactiveSession => 'Target session';
  @override
  String proactiveSessionChosen(String id) => 'Sent to $id';
  @override
  String get proactiveSessionEmpty => 'No live session. Open a chat in DSH first, then come back.';
  @override
  String get proactiveSessionHint => 'Not chosen yet: nothing is sent. Tap to pick a live session.';
  @override
  String get proactiveSessionIdle => 'Idle, ready to receive';
  @override
  String get proactiveSessionNone => 'None';
  @override
  String get proactiveSessionNoneHint => 'With no target session, no proactive message is sent';
  @override
  String get proactiveSessionRunning => 'Busy; it will be refused, not interrupted';
  @override
  String proactiveSessionUnavailable(String error) => 'Couldn\'t read the session list ($error); check that the local engine is connected and the plugin is loaded';
  @override
  String get proactiveState => 'Right now';
  @override
  String get proactiveSubtitle => 'DSH says something on its own every so often, or after you open a chosen app. Every rule runs on this phone; when the model is not available it says nothing at all.';
  @override
  String get proactiveTitle => 'Proactive messages';
  @override
  String get proactiveTransportMissing => 'Not connected to the engine yet: local text only, the model is not called';
  @override
  String get proactiveTransportReady => 'Connected to the engine on this machine, so it can really ask the model';
  @override
  String proactiveWakeMode(String mode) => 'Wake mode: $mode';
  @override
  String get proactiveWakeRestricted => 'This phone restricts background wakeups, so a proactive message is only attempted while DSH is open or the screen changes. For more reliable timing, allow DSH to run in the background and to autostart in system settings.';
  @override
  String get voiceOutCompanion => 'Little fish’s voice';
  @override
  String get voiceOutEnable => 'External voice';
  @override
  String get voiceOutEnableHint => 'Shared by the official, big fish and little fish';
  @override
  String get voiceOutFooter => 'The words are sent to the service you choose. Off keeps them on the phone’s own voice; nothing leaves it.';
  @override
  String get voiceOutKey => 'Key';
  @override
  String get voiceOutKeySet => 'Set';
  @override
  String get voiceOutKeyUnset => 'Not set';
  @override
  String get voiceOutOfficial => 'The official’s voice';
  @override
  String get voiceOutPreview => 'Listen';
  @override
  String get voiceOutPreviewFailed => 'Couldn’t be played. Try another service or voice.';
  @override
  String get voiceOutPreviewText => 'This bout is on the record.';
  @override
  String get voiceOutSection => 'Voice output';
  @override
  String get voiceOutService => 'Service';
  @override
  String get voiceOutStrategist => 'Big fish’s voice';
  @override
  String get voiceOutVoiceFollow => 'The service’s default';
  @override
  String get wallActionAppeal => 'Appeal';
  @override
  String get wallActionSubmit => 'Submit evidence';
  @override
  String get wallAppealEvidence => 'New physical evidence';
  @override
  String get wallAppealEvidenceHint => 'A file path, "screenshot", or "SomeApp 40 minutes". The one you already handed in doesn\'t count as new.';
  @override
  String get wallAppealLeft => '{left} appeals left today';
  @override
  String get wallAppealNoEvidence => 'An appeal has to carry new physical evidence.';
  @override
  String get wallAppealNoTarget => 'Pick a verdict first.';
  @override
  String get wallAppealNone => 'No appeals left today';
  @override
  String get wallAppealSend => 'Send appeal';
  @override
  String get wallAppealTarget => 'Which verdict';
  @override
  String get wallBubbleRule => 'Rule change';
  @override
  String get wallBubbleSubmit => 'Submitted';
  @override
  String get wallBubbleVerdict => 'Verdict';
  @override
  String get wallRefresh => 'Refresh';
  @override
  String get wallStatusLocked => 'Locked';
  @override
  String get wallStatusOpen => 'Open';
  @override
  String get wallStatusUnknown => 'Can\'t read the wall\'s status';
  @override
  String get wallStatusUntil => 'Locked until {time}';
  @override
  String get wallStatusWhy => 'Reason: {why}';
  @override
  String get wallThreadEmpty => 'No reviews yet. The wall only speaks after you hand in something.';
  @override
  String get wallThreadFailed => 'Can\'t read the wall\'s record: {reason}';
  @override
  String get wallTitle => 'Wall';
}

class _DafeiyuStringsZh extends DafeiyuStrings {
  const _DafeiyuStringsZh();

  @override
  String get boutVoiceDone => '这一局结束了。';
  @override
  String get boutVoiceFallbackIn => '这次没听清，改成打字。';
  @override
  String get boutVoiceFallbackOut => '声音用不了，这一句改成文字。';
  @override
  String get boutVoiceHeardLabel => '你刚才说';
  @override
  String get boutVoiceIntro => '局官念一句，你出声答一句。念的时候不算你的时间，念完才开始计时。';
  @override
  String get boutVoiceJudging => '在判这一句';
  @override
  String get boutVoiceListening => '在听你说';
  @override
  String get boutVoiceNoBout => '现在没有在跑的局。先在局里开一局，再回来按开始。';
  @override
  String get boutVoiceSayLabel => '局官';
  @override
  String get boutVoiceSpeaking => '局官在念';
  @override
  String get boutVoiceStart => '开始';
  @override
  String get boutVoiceStop => '停';
  @override
  String get boutVoiceSubmit => '交这句';
  @override
  String get boutVoiceWaitAsk => '等下一拍';
  @override
  String get boutVoiceWaitText => '没听见。打一句也行，或者按停。';
  @override
  String get catalogBoutVoice => '局 · 语音';
  @override
  String get catalogBoutVoiceSummary => '局官念题，你出声答';
  @override
  String interceptActive(String package) => '正在拦：$package';
  @override
  String get interceptAddTarget => '添加娱乐应用';
  @override
  String get interceptAddTargetHint => '填 Android 包名，例如 tv.danmaku.bili';
  @override
  String get interceptAddTargetInvalid => '包名不能为空，也不能有空格';
  @override
  String get interceptCooldown => '冷却（分钟）';
  @override
  String get interceptDailyMax => '每日上限';
  @override
  String get interceptDailyMaxOff => '不限次数';
  @override
  String get interceptEnabled => '开启娱乐锁机';
  @override
  String get interceptEnabledHint => '开启后每次打开下面清单里的娱乐 APP 都会进入锁屏；冷却期内不会重复弹窗。';
  @override
  String get interceptLedger => '行为拦截账';
  @override
  String get interceptLedgerBlocked => '真的拦住';
  @override
  String get interceptLedgerExited => '用户退出';
  @override
  String get interceptLedgerPaused => '暂停';
  @override
  String get interceptLedgerReleased => '用户放行';
  @override
  String get interceptLedgerTriggers => '触发';
  @override
  String get interceptMode => '拦截参数';
  @override
  String get interceptNoTargets => '尚未添加任何娱乐应用';
  @override
  String get interceptOutcomeBlocked => '拦住了，还没有结局';
  @override
  String get interceptOutcomeExited => '紧急退出';
  @override
  String get interceptOutcomeNotShown => '没显示出来';
  @override
  String get interceptOutcomePaused => '暂停';
  @override
  String get interceptOutcomeReleased => '放行';
  @override
  String get interceptPause => '暂停与恢复';
  @override
  String get interceptPauseHint => '暂停期间什么都不拦，到点自动恢复。';
  @override
  String get interceptPauseNow => '立刻暂停 30 分钟';
  @override
  String interceptPausedUntil(String time) => '已暂停，到 $time 恢复';
  @override
  String get interceptReceipts => '最近回执';
  @override
  String get interceptReceiptsEmpty => '还没有回执';
  @override
  String interceptRecoveryIssue(String error) => '启动时读到坏存档：$error';
  @override
  String get interceptRefresh => '刷新';
  @override
  String get interceptRelease => '放行时长（分钟）';
  @override
  String get interceptRemoveTarget => '移除';
  @override
  String get interceptResume => '恢复拦截';
  @override
  String interceptStorageError(String error) => '存档写入失败：$error';
  @override
  String get interceptSubtitle => '进入已标记的娱乐 APP 时强制锁屏：支持严格锁机、专注联动与冷静训练，避免误放行后锁不住。';
  @override
  String get interceptTargets => '受控娱乐应用';
  @override
  String get interceptTargetsHint => '支持一键勾选常用视频/游戏或从本机已安装应用中多选；移出后立即停止锁机。';
  @override
  String get interceptTitle => '娱乐锁机';
  @override
  String get interceptTraining => '训练题长度（秒）';
  @override
  String get interceptUnavailable => '这个版本没有接入拦截内核';
  @override
  String get interceptUnavailableHint => '只有手机端完整版本才有。桌面版和当前构建里没有 dsh/intercept 通道。';
  @override
  String get interceptWhitelist => '白名单';
  @override
  String get interceptWhitelistEmpty => '白名单是空的';
  @override
  String get interceptWhitelistHint => '白名单里的包名永远不拦，哪怕在目标清单里。';
  @override
  String get loopA11yNeeded => '需要先在系统设置里开启 DSH 的无障碍服务';
  @override
  String get loopA11yReady => '无障碍服务已连接';
  @override
  String get loopA11yWaiting => '无障碍已开启，等待服务连接';
  @override
  String get loopClear => '清空回执';
  @override
  String get loopCooldown => '冷却时间';
  @override
  String get loopCooldownHint => '两次弹出之间至少隔这么久，点一下切换';
  @override
  String loopCounters(int events, int readings, String app) => '收到 $events 个窗口事件，复核 $readings 次 · 最近前台 $app';
  @override
  String get loopDailyCap => '每天上限';
  @override
  String get loopDailyCapHint => '一天最多弹几次，过零点重新算';
  @override
  String get loopDailyCapOff => '不限次数';
  @override
  String loopDailyCapValue(int used, int cap) => '今天 $used/$cap 次';
  @override
  String get loopDiagnostics => '无障碍诊断';
  @override
  String get loopDiagnosticsClear => '清空诊断';
  @override
  String loopDiagnosticsLastRead(String time) => '上次成功读到前台：$time';
  @override
  String loopDiagnosticsSummary(int events, int readings, int faults) => '$events 个窗口事件、$readings 次前台复核、$faults 次失败';
  @override
  String get loopEnabled => '开启触发测试';
  @override
  String get loopEnabledHint => 'DSH 正在操作手机或有确认在等你时不会弹出';
  @override
  String get loopEntrySubtitle => '打开指定 APP 时弹出测试页（实验）';
  @override
  String get loopEntryTitle => '触发测试';
  @override
  String get loopExport => '导出回执（JSON 复制到剪贴板）';
  @override
  String loopExportDone(int count) => '已把 $count 条回执复制到剪贴板';
  @override
  String get loopFaultAttach => '连接失败';
  @override
  String get loopFaultEvent => '窗口事件出错';
  @override
  String get loopFaultRead => '读前台失败';
  @override
  String get loopFaultSurface => '弹出问题失败';
  @override
  String get loopFaultVerify => '触发判定出错';
  @override
  String get loopNoApps => '没有找到可选的 APP';
  @override
  String get loopPageSubtitle => '第一条闭环：打开测试 APP → 弹出测试页 → 点返回 → 记一条回执。只读前台 APP 的包名，不读页面内容。';
  @override
  String get loopPickApp => '选择测试 APP';
  @override
  String get loopQuietHours => '免打扰时段';
  @override
  String get loopQuietHoursHint => '这段时间里不弹测试页，可以跨零点。点一下切换。';
  @override
  String get loopQuietHoursOff => '不限';
  @override
  String loopQuietHoursValue(String start, String end) => '$start–$end';
  @override
  String get loopReceipts => '回执';
  @override
  String get loopReceiptsEmpty => '还没有回执';
  @override
  String loopRecoveredFromBackup(String file) => '回执文件损坏，已从备份（$file）恢复';
  @override
  String get loopRefresh => '刷新';
  @override
  String get loopResultAbandoned => '重启后关闭';
  @override
  String get loopResultBack => '按了系统返回';
  @override
  String get loopResultDismissed => '离开了页面';
  @override
  String get loopResultError => '出错';
  @override
  String get loopResultOpen => '进行中';
  @override
  String get loopResultReturned => '点了返回';
  @override
  String get loopResultSuppressed => '未触发';
  @override
  String get loopResultTimeout => '超时未回应';
  @override
  String get loopResultUnavailable => '页面没能弹出';
  @override
  String loopSeconds(int seconds) => '$seconds 秒';
  @override
  String loopStorageError(String error) => '回执保存失败：$error';
  @override
  String get loopSurfaceNotification => '通过通知';
  @override
  String get loopTargetApp => '测试 APP';
  @override
  String get loopTargetNone => '未选择';
  @override
  String get loopTimeout => '等你回应的时间';
  @override
  String get loopTimeoutHint => '超时就记成「超时未回应」，点一下切换';
  @override
  String loopVisitShort(String id) => '访问 $id';
  @override
  String get mountAccept => '点头，挂上去';
  @override
  String get mountAccepted => '挂上了';
  @override
  String get mountActions => '接下来';
  @override
  String get mountAddGiven => '补一条已知条件';
  @override
  String get mountAddStep => '补一条结论';
  @override
  String get mountAdded => '挂上了';
  @override
  String get mountCardOpen => '已经有一张开着的卡，先停放或关掉它。';
  @override
  String get mountClose => '关掉这张卡';
  @override
  String get mountCloseHint => '关掉之后只读，卡还在但不再进每轮注入。';
  @override
  String get mountClosed => '关掉了';
  @override
  String get mountDrop => '不要这条';
  @override
  String get mountDropped => '不要了';
  @override
  String get mountEmptyHint => '开始推一件事的时候开一张，回头就不用重新问「我在解决什么」。';
  @override
  String get mountFailed => '读不到挂载卡，稍后再试';
  @override
  String get mountFromGivens => '由第 {from} 条已知推出';
  @override
  String get mountFromSource => '出处：账本第 {src} 行';
  @override
  String get mountGivenPrompt => '他已经确认的一条事实';
  @override
  String get mountGivens => '已知条件';
  @override
  String get mountGivensEmpty => '还没有已知条件。他手上已经确认的事实，一条条挂上来。';
  @override
  String get mountGivensFull => '已知条件满了（最多 5 条），先删一条再来。';
  @override
  String get mountGoal => '目标';
  @override
  String get mountGoalEmpty => '开卡要先有一句目标。';
  @override
  String get mountGoalPrompt => '这次要解决什么（尽量用他原话）';
  @override
  String get mountHandWritten => '他手写的';
  @override
  String get mountLoading => '正在读挂载卡…';
  @override
  String get mountMiniAsk => '他说的和卡上对不上：继续这张，还是先停放？';
  @override
  String get mountMiniCounts => '已知 {givens}/{maxGivens} · 推到 {steps}/{maxSteps}';
  @override
  String get mountNeedsSource => '提议要带账本行号，没有出处的不提议。';
  @override
  String get mountNoCard => '现在没有开着的卡。';
  @override
  String get mountNotUserText => '卡上只能放他自己的原话，不能放总结。';
  @override
  String get mountOpen => '开一张新卡';
  @override
  String get mountOpenHint => '一句话说清这次要解决什么。他原话最好。';
  @override
  String get mountOpened => '开卡了';
  @override
  String get mountOtherCards => '别的卡';
  @override
  String get mountPark => '先停起来';
  @override
  String get mountParkHint => '说一句停在哪，下次回来从这儿接。';
  @override
  String get mountParkNeedsWhy => '停放要说一句停在哪。';
  @override
  String get mountParkPrompt => '停在哪：推到哪一步、回来从哪接';
  @override
  String get mountParked => '停好了';
  @override
  String get mountParkedWhy => '停在哪：{why}';
  @override
  String get mountPending => '她提议挂一条';
  @override
  String get mountPendingGiven => '已知条件';
  @override
  String get mountPendingStep => '中间结论：点头之后还要指明由哪几条已知推出';
  @override
  String get mountRefresh => '刷新';
  @override
  String get mountResume => '接着推这张';
  @override
  String get mountResumeHint => '回到停下的地方，目标和已知都还在。';
  @override
  String get mountResumed => '接着推';
  @override
  String get mountStatusClosed => '已关掉的卡';
  @override
  String get mountStatusNone => '还没有卡';
  @override
  String get mountStatusOpen => '开着的卡';
  @override
  String get mountStatusParked => '停着的卡';
  @override
  String get mountStepNeedsGiven => '卡上还没有已知条件，结论无从推起。';
  @override
  String get mountStepPick => '这条结论由哪几条已知推出';
  @override
  String get mountStepPrompt => '推到哪儿了（一句话）';
  @override
  String get mountSteps => '中间结论';
  @override
  String get mountStepsEmpty => '还没有结论。结论要写清由哪几条已知推出来。';
  @override
  String get mountStepsFull => '结论满了（最多 5 条），先删一条再来。';
  @override
  String get mountSubtitle => '推导到一半别断。目标 / 已知 / 推到哪，都放他自己的话。';
  @override
  String get mountTitle => '挂载卡';
  @override
  String get phoneDisclosureTriggerBody => '只有你打开「触发测试」或「主动搭话」之后，DSH 才会读取当前前台 APP 的名称（包名）：触发测试用它判断你有没有打开测试 APP；两者都用它避开系统设置、权限和安装页面，不在这些页面上弹出。不读页面内容，不录屏，不上传。随时可以关掉。';
  @override
  String get phoneDisclosureTriggerTitle => '触发测试与主动搭话（默认关闭）';
  @override
  String get phoneDndAdbReady => '自连 ADB · 可以用了';
  @override
  String get phoneDndAdbStatus => '自连 ADB · {reason}';
  @override
  String get phoneDndEnable => '启用不打扰模式';
  @override
  String get phoneDndPairOnce => '配对一次（不用装别的 App）';
  @override
  String get phoneDndShizukuReady => 'Shizuku · 可以用了';
  @override
  String get phoneDndShizukuStatus => 'Shizuku · {reason}';
  @override
  String get phoneDndUseShizuku => '用 Shizuku';
  @override
  String get phoneDndUsingAdb => '正在使用：自连 ADB';
  @override
  String get phoneDndUsingShizuku => '正在使用：Shizuku';
  @override
  String get phoneGeminiEmptyTopic => '连麦（没说主题）';
  @override
  String get phoneGeminiEnd => '结束连麦';
  @override
  String get phoneGeminiEnded => '连麦结束，锁已恢复';
  @override
  String get phoneGeminiFailed => '没接上大肥鱼，稍后再试';
  @override
  String get phoneGeminiHint => '连麦时只许 Gemini，别的 App 一开就锁；电话、短信、地图、支付不受限。';
  @override
  String get phoneGeminiInterrogate => '审讯摸鱼';
  @override
  String get phoneGeminiMinutes => '时长';
  @override
  String get phoneGeminiMode => '怎么陪';
  @override
  String get phoneGeminiRunning => '进行中：{topic} · 还剩 {minutes} 分钟 · 只许 Gemini';
  @override
  String get phoneGeminiStart => '开一场连麦';
  @override
  String get phoneGeminiStarted => '连麦开始了：只许 Gemini';
  @override
  String get phoneGeminiStudy => '学习陪练';
  @override
  String get phoneGeminiThink => '共脑想事';
  @override
  String get phoneGeminiTitle => 'Gemini 互动';
  @override
  String get phoneGeminiTopic => '这次要做什么（线代第三章、周报…）';
  @override
  String get phoneWallHint => '锁和放按他清醒时定下的规则执行，聊天里改不了；想多玩一段，等晚上对账后的申请窗口找鲸澜。';
  @override
  String get phoneWallLocked => '现在锁着：{why}';
  @override
  String get phoneWallQuota => '本周还能放松 {days} 天';
  @override
  String get phoneWallTitle => '墙（规则执行）';
  @override
  String get phoneWallTodayChanged => '今天改过规则';
  @override
  String get phoneWallTodaySame => '今天没有修改';
  @override
  String get phoneWallUnlocked => '现在没锁';
  @override
  String proactiveActiveStage(String stage) => '正在处理：$stage';
  @override
  String get proactiveDaily => '每天上限';
  @override
  String get proactiveDailyHint => '一天最多几次，过零点重新算；没送达的不算';
  @override
  String proactiveDailyValue(int count) => '$count 次';
  @override
  String get proactiveEnabled => '开启主动搭话';
  @override
  String get proactiveEnabledHint => '默认关闭。开启后会按下面的间隔随机找你；DSH 正在操作手机、有确认在等你、锁屏或免打扰时一律让路。';
  @override
  String get proactiveGap => '最短间隔';
  @override
  String get proactiveGapHint => '两次主动搭话之间至少隔这么久，实际间隔在这个范围和「最长间隔」之间随机';
  @override
  String get proactiveGapMax => '最长间隔';
  @override
  String get proactiveGapMaxHint => '随机间隔的上限，点一下切换';
  @override
  String proactiveGapValue(int min, int max) => '$min–$max 分钟';
  @override
  String get proactiveGateFree => '现在可以让路了：没有 DSH 任务，也不在黑名单页面上';
  @override
  String proactiveGateHold(String reason) => '正在让路：$reason';
  @override
  String get proactiveHoldA11y => '无障碍没连上';
  @override
  String get proactiveHoldDshPrompt => '有确认在等你回应';
  @override
  String get proactiveHoldDshTask => 'DSH 正在操作手机';
  @override
  String get proactiveHoldLocked => '锁屏了';
  @override
  String get proactiveHoldProtected => '前台是权限/安全页面';
  @override
  String get proactiveHoldScreenOff => '屏幕关着';
  @override
  String get proactiveMarkAccepted => '已交给 Agent';
  @override
  String get proactiveMarkDelivered => '已送出';
  @override
  String get proactiveMarkGenerated => '模型已生成';
  @override
  String get proactiveMarkResponded => '你回应了';
  @override
  String get proactiveMarkShown => '界面显示了';
  @override
  String proactiveMinutes(int minutes) => '$minutes 分钟';
  @override
  String get proactiveOutcomeAcked => '你点了「我看到了」';
  @override
  String get proactiveOutcomeDismissed => '你划掉了';
  @override
  String get proactiveOutcomeIgnoredSeen => '显示了但没回应';
  @override
  String get proactiveOutcomeNotDelivered => '没能送到';
  @override
  String get proactiveOutcomeOpen => '进行中';
  @override
  String get proactiveOutcomeResponded => '你回应了';
  @override
  String get proactiveOutcomeUnconfirmed => '通知发了，不知道你看没看';
  @override
  String get proactivePickSession => '选一个会话';
  @override
  String proactiveQueued(int count) => '排队中：$count 条';
  @override
  String get proactiveQuiet => '免打扰时段';
  @override
  String get proactiveQuietHint => '这段时间里不主动搭话，可以跨零点，点一下切换';
  @override
  String proactiveQuietValue(String start, String end) => '$start–$end';
  @override
  String get proactiveReceipts => '回执';
  @override
  String get proactiveReceiptsEmpty => '还没有记录';
  @override
  String proactiveRecoveryIssue(String issue) => '上次的状态读不出来：$issue';
  @override
  String get proactiveSession => '目标会话';
  @override
  String proactiveSessionChosen(String id) => '发到 $id';
  @override
  String get proactiveSessionEmpty => '没有活着的会话。先在 DSH 里开一个对话，再回来选。';
  @override
  String get proactiveSessionHint => '还没选：主动搭话不会发出去。点一下选一个活着的会话。';
  @override
  String get proactiveSessionIdle => '空闲，可以发';
  @override
  String get proactiveSessionNone => '不指定';
  @override
  String get proactiveSessionNoneHint => '不指定目标会话，主动搭话就不会发出';
  @override
  String get proactiveSessionRunning => '正在忙，发了会被拒（不会打断它）';
  @override
  String proactiveSessionUnavailable(String error) => '读不到会话列表（$error），先确认本机引擎已连上、插件已加载';
  @override
  String get proactiveState => '现在的状态';
  @override
  String get proactiveSubtitle => 'DSH 每隔一段随机时间，或者在你打开某个 APP 之后，主动跟你说一句话。全部规则在本机执行，模型不可用时就不说话。';
  @override
  String get proactiveTitle => '主动搭话';
  @override
  String get proactiveTransportMissing => '还没连上引擎，现在只会用本地文案，不会调用模型';
  @override
  String get proactiveTransportReady => '已经连上本机引擎，可以真的问模型';
  @override
  String proactiveWakeMode(String mode) => '唤醒方式：$mode';
  @override
  String get proactiveWakeRestricted => '这台手机限制了后台唤醒，所以只在你打开 DSH、或者屏幕有变化的时候才会尝试主动搭话。想更准时，可以在系统设置里允许 DSH 后台运行和自启动。';
  @override
  String get voiceOutCompanion => '小鱼音色';
  @override
  String get voiceOutEnable => '外接语音';
  @override
  String get voiceOutEnableHint => '局官、大鱼、小鱼共用这一档声音';
  @override
  String get voiceOutFooter => '念出来的文字会发给你选的服务。关掉就只用手机自带语音，文字不出手机。';
  @override
  String get voiceOutKey => '密钥';
  @override
  String get voiceOutKeySet => '已填写';
  @override
  String get voiceOutKeyUnset => '未填写';
  @override
  String get voiceOutOfficial => '局官音色';
  @override
  String get voiceOutPreview => '试听';
  @override
  String get voiceOutPreviewFailed => '没能念出来，换个服务或音色再试';
  @override
  String get voiceOutPreviewText => '这一局，我记下了。';
  @override
  String get voiceOutSection => '声音输出';
  @override
  String get voiceOutService => '服务';
  @override
  String get voiceOutStrategist => '大鱼音色';
  @override
  String get voiceOutVoiceFollow => '跟随服务默认';
  @override
  String get wallActionAppeal => '申诉';
  @override
  String get wallActionSubmit => '提交证据';
  @override
  String get wallAppealEvidence => '新的物理证据';
  @override
  String get wallAppealEvidenceHint => '文件路径、「截图」，或「某 App N 分钟」。上次交过的那份不算新的。';
  @override
  String get wallAppealLeft => '今天还能申诉 {left} 次';
  @override
  String get wallAppealNoEvidence => '申诉必须附一份新的物理证据。';
  @override
  String get wallAppealNoTarget => '先选一条判定，再申诉。';
  @override
  String get wallAppealNone => '今天的申诉用完了';
  @override
  String get wallAppealSend => '提交申诉';
  @override
  String get wallAppealTarget => '申诉哪一条判定';
  @override
  String get wallBubbleRule => '规则变更';
  @override
  String get wallBubbleSubmit => '提交';
  @override
  String get wallBubbleVerdict => '判定';
  @override
  String get wallRefresh => '刷新';
  @override
  String get wallStatusLocked => '锁着';
  @override
  String get wallStatusOpen => '开着';
  @override
  String get wallStatusUnknown => '读不到墙的状态';
  @override
  String get wallStatusUntil => '锁到 {time}';
  @override
  String get wallStatusWhy => '原因：{why}';
  @override
  String get wallThreadEmpty => '还没有审查记录。交一份完成物，墙才会说话。';
  @override
  String get wallThreadFailed => '读不到墙的记录：{reason}';
  @override
  String get wallTitle => '墙';
}

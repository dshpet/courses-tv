# Emulator checks

Manual checks of what the unit and Chromium tests cannot cover: Android focus and key routing, the
TV keyboard, the website's sign-in dialog, the live API and playback. Run them on a Google TV
emulator with your own account, real key events (`adb -s <emulator> shell input keyevent …`) and the
read-only build (`assembleReadOnly`): the release build, except that nothing is sent to the account
(each change is logged as `Not sent (read-only build)`; debug builds behave the same). Checks of
changes to the account (progress saves, My List, Remove, Mark) need the release build and change
your account.

Ground rules: keep credentials out of logs and screenshots (type them with `adb shell input text`
from a script, for example from the git-ignored `.env`); no optional cookie group switched on.
Before moving between sign-in fields, close the TV keyboard (`adb shell dumpsys input_method` shows
`mInputShown=true` while it is open): with it open, the D-pad moves inside the keyboard, so the next
value lands in the wrong field and the keyboard's suggestion strip can show it. Playing a lecture
saves progress to the account once it passes 2 minutes: test with lectures you are already watching,
or leave a new one before 2:00. Remove from Continue Watching changes the account's row on the
website too. Send one key per `adb shell input keyevent` call: several keys in one call arrive faster
than the screen redraws.

## 1. First run and sign-in
`adb shell pm clear` the app, launch it: the welcome screen with Sign in focused. Select → the
website with its sign-in dialog. With the cookie banner: Down → Accept All, Right → Cookie Settings,
Select → the first switch, Down to Done, Select (essential only). Type the email, close the
keyboard, Down, the password, close the keyboard, Down to Sign In, Select → the screen closes and
Home loads with Continue Watching. Back with the dialog open closes the dialog; a second Back leaves.

## 2. Home and browsing
Home opens with the first card focused. From the second start on, its rows show at once (what the
TV kept from last time) and are read again behind them: `am start -W` and the `Fully drawn` log line
time it. A reload that puts another card first in a row whose first card has the focus moves the
focus to the new first card; a row at its start stays at its start (check by editing the kept
progress in a debug build's `cache/api/` so another course is the latest, then a cold start). Course cards in every row and grid (not Continue Watching,
which shows its lecture) carry a bar for the share of the course watched, or a check once its last
lecture is finished; they appear a moment after the rows. A course card's hero shows the teacher,
lecture count and hours; Recently Watched heroes show the course description. Right/Left move along a row, which keeps the focused card at
the left edge; Down/Up move between rows, which dock under the hero; the hero shows the focused
title, its details and picture. Up from the first row reaches the menu; moving along the menu
switches tabs; Back from the content goes to the menu, from another tab to Home, from Home out.
Subjects: a grid of subjects then learning paths; Select → the subject's rows. My List: a grid.
Continue Watching: one card per course at its next lecture, at most 10, no finished courses; Select
held on a card → Play / Resume lecture N, Play from the beginning (lectures under way: the player
starts at 0:00), Go to course (the course at that lecture), Remove (the first Select only shows
what it does; the second removes); Back closes the menu. Before any Select in these menus, take a
screenshot to check which item has the focus: the number of items differs between cards. Recently Watched: course cards, most recent first ("Last watched today · N lectures
finished"); Select opens the course.

## 3. Search
Down from the menu onto the field opens the TV keyboard. Typing filters as you go ("roman history"
→ 8 courses). Back closes the keyboard, Down moves to the results, Select opens a course.

## 4. Course
Play/Resume is focused: "Resume lecture N" with the time left when a lecture past the furthest
finished one is under way, "Play lecture N" for the one after the furthest finished, "Watch again"
when the course is finished up to its last lecture (a lecture replayed behind that point doesn't
change it). Down from the buttons goes to that lecture; Up from the first lecture goes back to Play.
My List toggles (✓ / +) and survives leaving and coming back. The lectures show watched, minutes left
and progress bars; the picture follows the focused lecture and darkens behind the list. Select
plays that lecture. Select held on a lecture → Play (Resume and Play from the beginning when under
way) and Mark as watched (Mark as not watched when finished); Back closes the menu. Marking changes
the account's progress, here and on the website. Play a lecture from
the list, Next lecture in the player, Back: the list shows the next lecture focused; started from
the Play button, the focus stays on Play. After the lectures, More from (the professor): the
professor's other courses; Up goes back to the last lecture. Guidebook (courses that have one):
"Loading the guidebook… N%" the first time, then a page; Right/Left turn pages, Down scrolls and at
a page's end goes on to the next, Select enlarges, Back returns to the course; it reopens at the page
last read.

## 5. Player
Starts by itself at the saved position. Select pauses/resumes and shows the controls; Left/Right
seek 10 s (held: 30 s); Down → Lectures, Captions, Settings, Next lecture; controls hide after
5 s of playing (not while the settings show). Lectures → the course page at this lecture, the player
gone from Back (from Home's Continue Watching: a new course page in its place). Settings → Quality
(Auto with the height playing, then the stream's heights) and Speed (0.75–2×); Select switches at
once; Left or Back closes; they apply to the next lecture and after a restart. With captions on, the
panel also has Caption size (Small, Normal, Large, Extra large): the captions change at once and move
left of the open panel; the panel scrolls to keep the chosen line in view. Captions follow the TV's
caption style: `adb shell settings put secure accessibility_captioning_preset 2` (yellow on black)
and `… accessibility_captioning_font_scale 1.5`, reopen the player; undo with `settings delete secure
…` for both.

Are you still watching: on a course already finished (lectures replayed stay finished, but each
lecture's end is saved again and the course moves to the front of Recently Watched), play a lecture,
then move each lecture to 40 s before its end with `adb shell cmd media_session dispatch
fast-forward` (15 s a press; media-session commands other than Play don't count as the remote) and
let it end. Lecture 1 plays on, 2 and 3 start by themselves, and 4 waits at 0:00 behind "Are you
still watching?" with Keep watching focused; `dumpsys power` lists no screen wake lock for the app.
Keep watching plays on; so does `cmd media_session dispatch play`, after which the first Select
pauses (the player has the focus back); Back leaves to the course page. Any remote key during
playback, Back included, restarts the count. In the last 20 s a
"Next lecture in N s" card shows; Select while it shows plays the next lecture. Back hides the
controls, then leaves; the course page then shows the new time left.

Slow network and errors: `adb -s <emulator> emu network speed 1:1` (1 kbit/s; airplane mode does not
cut the emulator's network), then Right ×10 past the buffer. Select while it buffers pauses (play
icon). Within about 30 s the "couldn't play" card shows, with no spinner behind it.
Back leaves the player at once; or `emu network speed full`, then Try again plays on from there.
Throttling makes the emulator drop and rebuild its network: saves in that moment fail (logged as
"Progress not saved"), and the Google TV launcher may come to the front with its account chooser.
Check `adb shell dumpsys activity activities | grep topResumedActivity` before sending more keys (a
Select there opens Google's add-account page), and reopen the app with its launcher intent
(`am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n …`; a bare
`-n` start puts a second Home on top). Seek back afterwards so the account keeps its position.

## 6. Screen awake and system media controls
While playing, `adb shell dumpsys power` lists a `SCREEN_BRIGHT_WAKE_LOCK` for the app; paused, it
is gone. `adb shell dumpsys media_session` shows the app's session PLAYING/PAUSED;
`adb shell cmd media_session dispatch pause|play` pauses and resumes. After leaving the player the
session is gone.

## 7. Leaving the app
Home during a lecture: playback pauses and progress is saved. Reopen → the player, paused, controls
shown.

## 8. Sign out
Account → Sign out → the welcome screen; relaunching stays signed out until signing in again.

## 9. Recreation
The app's process ended in the background, as Google TV does to free memory: open a course and a
lecture, Next lecture, Home, then `adb shell am kill io.github.dshpet.coursestv` (it only ends a
process in the background) and reopen the app → the player on the lecture it had reached (not the
one it was opened with), at the second it was at (also in a lecture's first 2 minutes), paused,
controls shown; while it is in the background, `dumpsys media_session` lists no session for the app; Back, Back: the course and the menu screen are
rebuilt with their content, the menu on the tab it showed. Next lecture saves a few seconds on the
next lecture, which then leads Continue Watching: play the first lecture again for a moment, near
where it was, so the account points back at it. (`settings put global always_finish_activities 1`
alone may not take effect on an emulator: the screens stay stopped.)

## 10. Without network
`adb shell cmd connectivity set-chain3-enabled true`, then
`adb shell cmd connectivity set-package-networking-enabled false io.github.dshpet.coursestv` cuts
this app off (connections already open may keep working: force-stop and reopen the app). Home shows
"Couldn't reach" with Try again; Search shows the same card, not "No courses match"; on a course page
opened before cutting, My List says it couldn't be changed and keeps its icon. Turn networking back
on: Try again loads; the first My List press only reads the list again. Afterwards
`set-chain3-enabled false`.

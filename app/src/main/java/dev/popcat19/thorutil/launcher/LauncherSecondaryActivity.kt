package dev.popcat19.thorutil.launcher

// Separate class + task affinity so the bottom display (SECONDARY_HOME) gets its own task
// instead of sharing (and stealing focus from) the top-screen home task.
class LauncherSecondaryActivity : LauncherActivity()

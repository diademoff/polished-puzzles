/*
 * android_stubs.c: the Android frontend hooks that the vendored engine
 * calls under #ifdef ANDROID.
 *
 * The oracle and fuzzpuzz are ordinary command-line programs, so the
 * hooks do nothing. android.c provides the real versions in the app.
 *
 * This file is project code. It is not vendored from upstream.
 */

#include "puzzles.h"

void android_completed(frontend *fe)
{
    (void)fe;
}

int allow_flash(frontend *fe)
{
    (void)fe;
    return 0;
}

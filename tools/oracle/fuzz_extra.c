/*
 * fuzz_extra.c: the drawing callbacks that nullfe.c does not provide.
 *
 * fuzzpuzz is linked with nullfe.c, which replaces drawing.c, so these
 * callbacks have no other definition in that binary. They do nothing,
 * which is correct for a fuzzing frontend.
 *
 * This file is project code. It is not vendored from upstream.
 */

#include "puzzles.h"

void changed_state(drawing *dr, int can_undo, int can_redo)
{
    (void)dr;
    (void)can_undo;
    (void)can_redo;
}

void purging_states(drawing *dr)
{
    (void)dr;
}

void inertia_follow(drawing *dr, bool is_solved)
{
    (void)dr;
    (void)is_solved;
}

void draw_thick_polygon(drawing *dr, float thickness, int *coords,
                        int npoints, int fillcolour, int outlinecolour)
{
    (void)dr;
    (void)thickness;
    (void)coords;
    (void)npoints;
    (void)fillcolour;
    (void)outlinecolour;
}

void draw_thick_circle(drawing *dr, float thickness, float cx, float cy,
                       float radius, int fillcolour, int outlinecolour)
{
    (void)dr;
    (void)thickness;
    (void)cx;
    (void)cy;
    (void)radius;
    (void)fillcolour;
    (void)outlinecolour;
}

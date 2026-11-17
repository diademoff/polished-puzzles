/*
 * oracle.c: host oracle for the parity tests.
 *
 * The oracle is the upstream C code built for the host. It speaks a
 * line-based JSON protocol: one request per line, one response per
 * line. It is stateless: the game state is passed in every request as
 * a serialised save file.
 *
 * This file is project code. It is not vendored from upstream.
 *
 * Protocol
 * --------
 * Requests (flat JSON objects):
 *
 *   {"op":"games"}
 *   {"op":"presets","game":"mines"}
 *   {"op":"new","game":"fifteen","params":"4x4","seed":"12345"}
 *   {"op":"id","state":"..."}
 *   {"op":"key","state":"...","x":0,"y":0,"k":76}
 *   {"op":"keySized","state":"...","w":411,"h":702,"x":0,"y":0,"k":512}
 *   {"op":"layout","state":"...","w":411,"h":702}
 *   {"op":"gestureSized","state":"...","w":411,"h":702,"x0":0,"y0":0,"x1":0,"y1":0,"steps":4}
 *   {"op":"pointsSized","state":"...","w":411,"h":702,"points":"48,48,512;48,48,512","trace":1}
 *   {"op":"undo","state":"..."}
 *   {"op":"redo","state":"..."}
 *   {"op":"solve","state":"..."}
 *   {"op":"text","state":"..."}
 *   {"op":"requestkeys","state":"..."}
 *   {"op":"requestkeys","state":"...","fullCursor":1}
 *   {"op":"status","state":"..."}
 *   {"op":"keys","state":"...","seq":"108 108 522"}
 *   {"op":"keys","state":"...","seq":"108","prefs":"cursor-mode=full"}
 *   {"op":"trace","state":"..."}
 *   {"op":"trace","state":"...","seq":"524","prefs":"fade-grounded=true"}
 *   {"op":"traceNew","game":"fifteen","params":"4x4","seed":"12345"}
 *   {"op":"traceWarm","state":"...","points":"48,48,512;64,64,515"}
 *   {"op":"randomBits","seed":"12345","bits":8,"count":10}
 *   {"op":"randomUpto","state":"...","limit":6,"count":10}
 *   {"op":"tree","ops":"a 5 1 f 5 t 7 i 0"}
 *   {"op":"matching","nl":3,"nr":4,"adj":"0 1;1 2;0 3"}
 *   {"op":"findloop","n":6,"edges":"0-1 1-2 2-0 3-4"}
 *   {"op":"dsf","kind":"normal","n":10,"ops":"m 0 1 m 2 3"}
 *   {"op":"divvy","w":5,"h":5,"k":5,"seed":"12345"}
 *   {"op":"laydomino","w":6,"h":5,"seed":"12345"}
 *   {"op":"latin","kind":"generate","o":5,"seed":"12345"}
 *   {"op":"latin","kind":"generateRect","w":4,"h":6,"seed":"12345"}
 *   {"op":"latin","kind":"solve","o":5,"grid":"1 0 0 0 0 ...","maxdiff":3}
 *   {"op":"latin","kind":"check","o":3,"grid":"1 2 3 2 3 1 3 1 2"}
 *   {"op":"tdq","n":10,"ops":"a 3 a 1 r f"}
 *   {"op":"sort","values":"5 3 9 1","mode":"asc"}
 *   {"op":"combi","r":2,"n":5}
 *   {"op":"grid","kind":"new","type":"square","w":5,"h":5}
 *   {"op":"grid","kind":"desc","type":"hats","w":5,"h":5,"seed":"12345"}
 *   {"op":"grid","kind":"validate","type":"triangular","w":5,"h":5,"desc":"0"}
 *   {"op":"grid","kind":"nearest","type":"square","w":5,"h":5,"x":10,"y":10}
 *   {"op":"grid","kind":"size","type":"square","w":5,"h":5}
 *
 * Responses (flat JSON objects):
 *
 *   {"ok":true,...}
 *   {"ok":false,"error":"..."}
 *
 * State responses carry: state, id, game, w, h, canundo, canredo,
 * and status. The status value follows midend_status(): 1 win, -1 lose,
 * 0 open.
 *
 * A trace response carries the recorded drawing operations for one
 * redraw of the state. See docs/adr/0003-strict-draw-traces.md.
 */

#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "puzzles.h"
#include "latin.h"
#include "matching.h"
#include "tree234.h"
#include "grid.h"

struct frontend {
    midend *me;
    int width;
    int height;
};

/* ------------------------------------------------------------------ */
/* Frontend callbacks.                                                */
/* ------------------------------------------------------------------ */

void fatal(const char *fmt, ...)
{
    va_list ap;

    fprintf(stderr, "fatal error: ");
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fprintf(stderr, "\n");
    exit(1);
}

void frontend_default_colour(frontend *fe, float *output)
{
    (void)fe;
    output[0] = output[1] = output[2] = 0.8F;
}

void get_random_seed(void **randseed, int *randseedsize)
{
    char *c = snewn(1, char);
    *c = 0;
    *randseed = c;
    *randseedsize = 1;
}

void activate_timer(frontend *fe) { (void)fe; }
void deactivate_timer(frontend *fe) { (void)fe; }

/* ------------------------------------------------------------------ */
/* Drawing API. The oracle compares state, not pixels, so every        */
/* operation is a no-op.                                              */
/* ------------------------------------------------------------------ */

struct blitter {
    int unused;
    /* The recording API uses the same structure to correlate
     * blitter_save and blitter_load with blitter_new. */
    int id;
    int w, h;
};

static void op_draw_text(drawing *dr, int x, int y, int fonttype,
                         int fontsize, int align, int colour,
                         const char *text)
{
    (void)dr; (void)x; (void)y; (void)fonttype; (void)fontsize;
    (void)align; (void)colour; (void)text;
}

static void op_draw_rect(drawing *dr, int x, int y, int w, int h, int colour)
{
    (void)dr; (void)x; (void)y; (void)w; (void)h; (void)colour;
}

static void op_draw_line(drawing *dr, int x1, int y1, int x2, int y2,
                         int colour)
{
    (void)dr; (void)x1; (void)y1; (void)x2; (void)y2; (void)colour;
}

static void op_draw_polygon(drawing *dr, const int *coords, int npoints,
                            int fillcolour, int outlinecolour)
{
    (void)dr; (void)coords; (void)npoints;
    (void)fillcolour; (void)outlinecolour;
}

static void op_draw_thick_polygon(drawing *dr, float thickness,
                                  const int *coords, int npoints,
                                  int fillcolour, int outlinecolour)
{
    (void)dr; (void)thickness; (void)coords; (void)npoints;
    (void)fillcolour; (void)outlinecolour;
}

static void op_draw_circle(drawing *dr, int cx, int cy, int radius,
                           int fillcolour, int outlinecolour)
{
    (void)dr; (void)cx; (void)cy; (void)radius;
    (void)fillcolour; (void)outlinecolour;
}

static void op_draw_thick_circle(drawing *dr, float thickness,
                                 float cx, float cy, float radius,
                                 int fillcolour, int outlinecolour)
{
    (void)dr; (void)thickness; (void)cx; (void)cy; (void)radius;
    (void)fillcolour; (void)outlinecolour;
}

static void op_draw_update(drawing *dr, int x, int y, int w, int h)
{
    (void)dr; (void)x; (void)y; (void)w; (void)h;
}

static void op_clip(drawing *dr, int x, int y, int w, int h)
{
    (void)dr; (void)x; (void)y; (void)w; (void)h;
}

static void op_unclip(drawing *dr) { (void)dr; }
static void op_start_draw(drawing *dr) { (void)dr; }
static void op_end_draw(drawing *dr) { (void)dr; }
static void op_status_bar(drawing *dr, const char *text)
{
    (void)dr; (void)text;
}

static blitter *op_blitter_new(drawing *dr, int w, int h)
{
    (void)dr; (void)w; (void)h;
    return snew(blitter);
}

static void op_blitter_free(drawing *dr, blitter *bl)
{
    (void)dr;
    sfree(bl);
}

static void op_blitter_save(drawing *dr, blitter *bl, int x, int y)
{
    (void)dr; (void)bl; (void)x; (void)y;
}

static void op_blitter_load(drawing *dr, blitter *bl, int x, int y)
{
    (void)dr; (void)bl; (void)x; (void)y;
}

static void op_line_width(drawing *dr, float width)
{
    (void)dr; (void)width;
}

static void op_line_dotted(drawing *dr, bool dotted)
{
    (void)dr; (void)dotted;
}

static char *op_text_fallback(drawing *dr, const char *const *strings,
                              int nstrings)
{
    (void)dr;
    if (nstrings < 1)
        return dupstr("");
    return dupstr(strings[0]);
}

static void op_changed_state(drawing *dr, int can_undo, int can_redo)
{
    (void)dr; (void)can_undo; (void)can_redo;
}

static void op_purging_states(drawing *dr) { (void)dr; }

static void op_draw_thick_line(drawing *dr, float thickness, float x1,
                               float y1, float x2, float y2, int colour)
{
    (void)dr; (void)thickness; (void)x1; (void)y1; (void)x2; (void)y2;
    (void)colour;
}

static void op_inertia_follow(drawing *dr, bool is_solved)
{
    (void)dr; (void)is_solved;
}

static const drawing_api oracle_drawing = {
    1,
    op_draw_text,
    op_draw_rect,
    op_draw_line,
    op_draw_polygon,
    op_draw_thick_polygon,
    op_draw_circle,
    op_draw_thick_circle,
    op_draw_update,
    op_clip,
    op_unclip,
    op_start_draw,
    op_end_draw,
    op_status_bar,
    op_blitter_new,
    op_blitter_free,
    op_blitter_save,
    op_blitter_load,
    NULL, /* begin_doc */
    NULL, /* begin_page */
    NULL, /* begin_puzzle */
    NULL, /* end_puzzle */
    NULL, /* end_page */
    NULL, /* end_doc */
    op_line_width,
    op_line_dotted,
    op_text_fallback,
    op_changed_state,
    op_purging_states,
    op_draw_thick_line,
    op_inertia_follow,
};

/* ------------------------------------------------------------------ */
/* Growable byte buffer and serialise helpers.                        */
/* ------------------------------------------------------------------ */

typedef struct {
    char *buf;
    int len;
    int cap;
} sbuf;

static void sbuf_reserve(sbuf *b, int extra)
{
    if (b->len + extra + 1 > b->cap) {
        int cap = b->cap ? b->cap : 256;
        while (cap < b->len + extra + 1)
            cap *= 2;
        b->buf = sresize(b->buf, cap, char);
        b->cap = cap;
    }
}

static void sbuf_add(sbuf *b, const void *data, int len)
{
    sbuf_reserve(b, len);
    memcpy(b->buf + b->len, data, len);
    b->len += len;
    b->buf[b->len] = '\0';
}

static void sbuf_write_cb(void *ctx, const void *data, int len)
{
    sbuf_add((sbuf *)ctx, data, len);
}

typedef struct {
    const char *buf;
    int len;
    int pos;
} rbuf;

static bool rbuf_read_cb(void *ctx, void *out, int len)
{
    rbuf *r = (rbuf *)ctx;

    if (len < 0 || r->pos + len > r->len)
        return false;
    memcpy(out, r->buf + r->pos, len);
    r->pos += len;
    return true;
}

/* ------------------------------------------------------------------ */
/* Recording drawing API.                                             */
/*                                                                    */
/* Every callback appends one JSON operation to `ops`, in call order. */
/* Numbers are integers in milli-units: round(value * 1000). See      */
/* docs/adr/0003-strict-draw-traces.md.                               */
/* ------------------------------------------------------------------ */

typedef struct {
    sbuf ops;
    int nops;
    int blitters;
    bool suppress_blitter_free;
    /*
     * When set, the drawing callbacks still change the blitter and
     * drawstate state but append nothing to `ops`. A warm trace uses it
     * for the first draw and for the input sequence, then clears the
     * buffer and records one redraw on the same drawstate.
     */
    bool discard;
    /*
     * The drawing object seen by the callbacks. A sized pointer
     * sequence needs it to reset the status cache before the final
     * redraw.
     */
    drawing *dr;
    /* The last status text the drawing layer emitted. */
    char *last_status;
} trace_ctx;

static void tc_printf(trace_ctx *t, const char *fmt, ...)
{
    va_list ap;
    char stack[256];
    char *buf = stack;
    int n;

    if (t->discard)
        return;
    va_start(ap, fmt);
    n = vsnprintf(stack, sizeof(stack), fmt, ap);
    va_end(ap);
    if (n < 0)
        fatal("cannot format a trace operation");
    if ((size_t)n >= sizeof(stack)) {
        buf = snewn(n + 1, char);
        va_start(ap, fmt);
        vsnprintf(buf, n + 1, fmt, ap);
        va_end(ap);
    }
    sbuf_add(&t->ops, buf, n);
    if (buf != stack)
        sfree(buf);
}

static void tc_string(trace_ctx *t, const char *s)
{
    if (t->discard)
        return;
    tc_printf(t, "\"");
    if (s) {
        for (; *s; s++) {
            unsigned char c = (unsigned char)*s;
            switch (c) {
              case '"': tc_printf(t, "\\\""); break;
              case '\\': tc_printf(t, "\\\\"); break;
              case '\b': tc_printf(t, "\\b"); break;
              case '\f': tc_printf(t, "\\f"); break;
              case '\n': tc_printf(t, "\\n"); break;
              case '\r': tc_printf(t, "\\r"); break;
              case '\t': tc_printf(t, "\\t"); break;
              default:
                if (c < 0x20)
                    tc_printf(t, "\\u%04x", c);
                else
                    sbuf_add(&t->ops, s, 1);
            }
        }
    }
    tc_printf(t, "\"");
}

static void tc_op_start(trace_ctx *t)
{
    if (t->discard)
        return;
    if (t->nops++)
        sbuf_add(&t->ops, ",", 1);
    tc_printf(t, "{");
}

static void tc_op_end(trace_ctx *t)
{
    tc_printf(t, "}");
}

static long tc_milli(float value)
{
    return (long)lroundf(value * 1000.0F);
}

static trace_ctx *tc_of(drawing *dr)
{
    return (trace_ctx *)dr->handle;
}

static void tr_draw_text(drawing *dr, int x, int y, int fonttype,
                         int fontsize, int align, int colour,
                         const char *text)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"text\",\"x\":%d,\"y\":%d,\"font\":%d,"
                 "\"size\":%d,\"align\":%d,\"c\":%d,\"text\":",
              x, y, fonttype, fontsize, align, colour);
    tc_string(t, text);
    tc_op_end(t);
}

static void tr_draw_rect(drawing *dr, int x, int y, int w, int h, int colour)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"rect\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,"
                 "\"c\":%d", x, y, w, h, colour);
    tc_op_end(t);
}

static void tr_draw_line(drawing *dr, int x1, int y1, int x2, int y2,
                         int colour)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"line\",\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d,"
                 "\"c\":%d", x1, y1, x2, y2, colour);
    tc_op_end(t);
}

static void tr_draw_polygon(drawing *dr, const int *coords, int npoints,
                            int fillcolour, int outlinecolour)
{
    trace_ctx *t = tc_of(dr);
    int i;

    tc_op_start(t);
    tc_printf(t, "\"op\":\"poly\",\"pts\":[");
    for (i = 0; i < npoints * 2; i++)
        tc_printf(t, i ? ",%d" : "%d", coords[i]);
    tc_printf(t, "],\"fill\":%d,\"outline\":%d", fillcolour, outlinecolour);
    tc_op_end(t);
}

static void tr_draw_thick_polygon(drawing *dr, float thickness,
                                  const int *coords, int npoints,
                                  int fillcolour, int outlinecolour)
{
    trace_ctx *t = tc_of(dr);
    int i;

    tc_op_start(t);
    tc_printf(t, "\"op\":\"thick_poly\",\"t\":%ld,\"pts\":[",
              tc_milli(thickness));
    for (i = 0; i < npoints * 2; i++)
        tc_printf(t, i ? ",%d" : "%d", coords[i]);
    tc_printf(t, "],\"fill\":%d,\"outline\":%d", fillcolour, outlinecolour);
    tc_op_end(t);
}

static void tr_draw_circle(drawing *dr, int cx, int cy, int radius,
                           int fillcolour, int outlinecolour)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"circle\",\"cx\":%d,\"cy\":%d,\"r\":%d,"
                 "\"fill\":%d,\"outline\":%d",
              cx, cy, radius, fillcolour, outlinecolour);
    tc_op_end(t);
}

static void tr_draw_thick_circle(drawing *dr, float thickness,
                                 float cx, float cy, float radius,
                                 int fillcolour, int outlinecolour)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"thick_circle\",\"t\":%ld,\"cx\":%ld,\"cy\":%ld,"
                 "\"r\":%ld,\"fill\":%d,\"outline\":%d",
              tc_milli(thickness), tc_milli(cx), tc_milli(cy),
              tc_milli(radius), fillcolour, outlinecolour);
    tc_op_end(t);
}

static void tr_draw_update(drawing *dr, int x, int y, int w, int h)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"update\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d",
              x, y, w, h);
    tc_op_end(t);
}

static void tr_clip(drawing *dr, int x, int y, int w, int h)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"clip\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d",
              x, y, w, h);
    tc_op_end(t);
}

static void tr_unclip(drawing *dr)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"unclip\"");
    tc_op_end(t);
}

static void tr_start_draw(drawing *dr)
{
    trace_ctx *t = tc_of(dr);

    t->dr = dr;
    tc_op_start(t);
    tc_printf(t, "\"op\":\"start_draw\"");
    tc_op_end(t);
    t->suppress_blitter_free = false;
}

static void tr_end_draw(drawing *dr)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"end_draw\"");
    tc_op_end(t);
}

static void tr_status_bar(drawing *dr, const char *text)
{
    trace_ctx *t = tc_of(dr);

    t->dr = dr;
    sfree(t->last_status);
    t->last_status = dupstr(text ? text : "");
    tc_op_start(t);
    tc_printf(t, "\"op\":\"status\",\"text\":");
    tc_string(t, text);
    tc_op_end(t);
}

static blitter *tr_blitter_new(drawing *dr, int w, int h)
{
    trace_ctx *t = tc_of(dr);
    blitter *bl = snew(blitter);

    bl->id = ++t->blitters;
    bl->w = w;
    bl->h = h;
    tc_op_start(t);
    tc_printf(t, "\"op\":\"blitter_new\",\"id\":%d,\"w\":%d,\"h\":%d",
              bl->id, w, h);
    tc_op_end(t);
    return bl;
}

static void tr_blitter_free(drawing *dr, blitter *bl)
{
    trace_ctx *t = tc_of(dr);

    /*
     * A replacement drawstate frees the old one before its first
     * start_draw. Those frees belong to a drawstate that is not in the
     * trace, so do not record them.
     */
    if (!t->suppress_blitter_free) {
        tc_op_start(t);
        tc_printf(t, "\"op\":\"blitter_free\",\"id\":%d", bl->id);
        tc_op_end(t);
    }
    sfree(bl);
}

static void tr_blitter_save(drawing *dr, blitter *bl, int x, int y)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"blitter_save\",\"id\":%d,\"x\":%d,\"y\":%d",
              bl->id, x, y);
    tc_op_end(t);
}

static void tr_blitter_load(drawing *dr, blitter *bl, int x, int y)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"blitter_load\",\"id\":%d,\"x\":%d,\"y\":%d",
              bl->id, x, y);
    tc_op_end(t);
}

static void tr_line_width(drawing *dr, float width)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"line_width\",\"t\":%ld", tc_milli(width));
    tc_op_end(t);
}

static void tr_line_dotted(drawing *dr, bool dotted)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"line_dotted\",\"dotted\":%s",
              dotted ? "true" : "false");
    tc_op_end(t);
}

static char *tr_text_fallback(drawing *dr, const char *const *strings,
                              int nstrings)
{
    (void)dr;
    if (nstrings < 1)
        return dupstr("");
    return dupstr(strings[0]);
}

static void tr_changed_state(drawing *dr, int can_undo, int can_redo)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"changed_state\",\"canundo\":%s,\"canredo\":%s",
              can_undo ? "true" : "false", can_redo ? "true" : "false");
    tc_op_end(t);
}

static void tr_purging_states(drawing *dr)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"purging_states\"");
    tc_op_end(t);
}

static void tr_draw_thick_line(drawing *dr, float thickness, float x1,
                               float y1, float x2, float y2, int colour)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"thick_line\",\"t\":%ld,\"x1\":%ld,\"y1\":%ld,"
                 "\"x2\":%ld,\"y2\":%ld,\"c\":%d",
              tc_milli(thickness), tc_milli(x1), tc_milli(y1),
              tc_milli(x2), tc_milli(y2), colour);
    tc_op_end(t);
}

static void tr_inertia_follow(drawing *dr, bool is_solved)
{
    trace_ctx *t = tc_of(dr);

    tc_op_start(t);
    tc_printf(t, "\"op\":\"inertia_follow\",\"solved\":%s",
              is_solved ? "true" : "false");
    tc_op_end(t);
}

static const drawing_api recording_drawing = {
    1,
    tr_draw_text,
    tr_draw_rect,
    tr_draw_line,
    tr_draw_polygon,
    tr_draw_thick_polygon,
    tr_draw_circle,
    tr_draw_thick_circle,
    tr_draw_update,
    tr_clip,
    tr_unclip,
    tr_start_draw,
    tr_end_draw,
    tr_status_bar,
    tr_blitter_new,
    tr_blitter_free,
    tr_blitter_save,
    tr_blitter_load,
    NULL, /* begin_doc */
    NULL, /* begin_page */
    NULL, /* begin_puzzle */
    NULL, /* end_puzzle */
    NULL, /* end_page */
    NULL, /* end_doc */
    tr_line_width,
    tr_line_dotted,
    tr_text_fallback,
    tr_changed_state,
    tr_purging_states,
    tr_draw_thick_line,
    tr_inertia_follow,
};

/* ------------------------------------------------------------------ */
/* A small flat JSON reader. It accepts objects with string or        */
/* integer values, which is all the protocol needs.                   */
/* ------------------------------------------------------------------ */

#define MAX_FIELDS 16

typedef struct {
    char *key;
    char *sval;
    long ival;
    bool is_int;
} jfield;

typedef struct {
    jfield fields[MAX_FIELDS];
    int count;
} jobject;

static const char *skip_ws(const char *p)
{
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')
        p++;
    return p;
}

static int hex_digit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int utf8_put(char *out, unsigned long cp)
{
    if (cp < 0x80) {
        out[0] = (char)cp;
        return 1;
    } else if (cp < 0x800) {
        out[0] = (char)(0xC0 | (cp >> 6));
        out[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    } else if (cp < 0x10000) {
        out[0] = (char)(0xE0 | (cp >> 12));
        out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char)(0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (char)(0xF0 | (cp >> 18));
    out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
    out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
    out[3] = (char)(0x80 | (cp & 0x3F));
    return 4;
}

/* Reads a JSON string. Returns the position after the closing quote,
 * or NULL on error. The result is allocated with smalloc. */
static const char *read_json_string(const char *p, char **out)
{
    sbuf b = {0};

    if (*p != '"')
        return NULL;
    p++;
    sbuf_reserve(&b, 0);
    b.buf[0] = '\0';

    while (*p && *p != '"') {
        if (*p == '\\') {
            char tmp[4];
            int n = 0;

            p++;
            switch (*p) {
              case '"': tmp[0] = '"'; n = 1; break;
              case '\\': tmp[0] = '\\'; n = 1; break;
              case '/': tmp[0] = '/'; n = 1; break;
              case 'b': tmp[0] = '\b'; n = 1; break;
              case 'f': tmp[0] = '\f'; n = 1; break;
              case 'n': tmp[0] = '\n'; n = 1; break;
              case 'r': tmp[0] = '\r'; n = 1; break;
              case 't': tmp[0] = '\t'; n = 1; break;
              case 'u': {
                int i;
                unsigned long cp = 0;
                for (i = 0; i < 4; i++) {
                    int d = hex_digit(p[1 + i]);
                    if (d < 0)
                        goto bad;
                    cp = (cp << 4) | (unsigned long)d;
                }
                p += 4;
                n = utf8_put(tmp, cp);
                break;
              }
              default:
                goto bad;
            }
            sbuf_add(&b, tmp, n);
            p++;
        } else {
            sbuf_add(&b, p, 1);
            p++;
        }
    }
    if (*p != '"')
        goto bad;
    *out = b.buf;
    return p + 1;

  bad:
    sfree(b.buf);
    return NULL;
}

static const char *read_json_int(const char *p, long *out)
{
    long sign = 1;
    long value = 0;
    const char *start = p;

    p = skip_ws(p);
    if (*p == '-') {
        sign = -1;
        p++;
    }
    if (*p < '0' || *p > '9')
        return start;
    while (*p >= '0' && *p <= '9') {
        value = value * 10 + (*p - '0');
        p++;
    }
    *out = sign * value;
    return p;
}

static bool parse_object(const char *line, jobject *obj)
{
    const char *p = skip_ws(line);

    memset(obj, 0, sizeof(*obj));
    if (*p != '{')
        return false;
    p = skip_ws(p + 1);
    if (*p == '}')
        return true;

    for (;;) {
        char *key = NULL;
        char *value = NULL;

        p = read_json_string(p, &key);
        if (!p)
            goto bad;
        p = skip_ws(p);
        if (*p != ':') {
            sfree(key);
            goto bad;
        }
        p = skip_ws(p + 1);

        if (obj->count >= MAX_FIELDS) {
            sfree(key);
            goto bad;
        }

        obj->fields[obj->count].key = key;
        obj->count++;
        if (*p == '"') {
            p = read_json_string(p, &value);
            if (!p)
                goto bad;
            obj->fields[obj->count - 1].sval = value;
            obj->fields[obj->count - 1].is_int = false;
        } else {
            long ival = 0;
            const char *q = read_json_int(p, &ival);
            if (q == p)
                goto bad;
            p = q;
            obj->fields[obj->count - 1].ival = ival;
            obj->fields[obj->count - 1].is_int = true;
        }

        p = skip_ws(p);
        if (*p == ',') {
            p = skip_ws(p + 1);
            continue;
        }
        if (*p == '}')
            return true;
        goto bad;
    }

  bad:
    while (obj->count > 0) {
        obj->count--;
        sfree(obj->fields[obj->count].key);
        sfree(obj->fields[obj->count].sval);
    }
    return false;
}

static void free_object(jobject *obj)
{
    int i;

    for (i = 0; i < obj->count; i++) {
        sfree(obj->fields[i].key);
        sfree(obj->fields[i].sval);
    }
    obj->count = 0;
}

static const char *field_string(const jobject *obj, const char *key)
{
    int i;

    for (i = 0; i < obj->count; i++)
        if (!strcmp(obj->fields[i].key, key))
            return obj->fields[i].sval;
    return NULL;
}

static bool field_int(const jobject *obj, const char *key, long *out)
{
    int i;

    for (i = 0; i < obj->count; i++)
        if (!strcmp(obj->fields[i].key, key) && obj->fields[i].is_int) {
            *out = obj->fields[i].ival;
            return true;
        }
    return false;
}

/* ------------------------------------------------------------------ */
/* JSON output.                                                       */
/* ------------------------------------------------------------------ */

static void put_json_string(const char *s)
{
    putchar('"');
    if (s) {
        for (; *s; s++) {
            unsigned char c = (unsigned char)*s;
            switch (c) {
              case '"': fputs("\\\"", stdout); break;
              case '\\': fputs("\\\\", stdout); break;
              case '\b': fputs("\\b", stdout); break;
              case '\f': fputs("\\f", stdout); break;
              case '\n': fputs("\\n", stdout); break;
              case '\r': fputs("\\r", stdout); break;
              case '\t': fputs("\\t", stdout); break;
              default:
                if (c < 0x20)
                    printf("\\u%04x", c);
                else
                    putchar(c);
            }
        }
    }
    putchar('"');
}

static void put_error(const char *msg)
{
    fputs("{\"ok\":false,\"error\":", stdout);
    put_json_string(msg);
    fputs("}\n", stdout);
}

/* ------------------------------------------------------------------ */
/* Game lookup and midend helpers.                                    */
/* ------------------------------------------------------------------ */

static int ci_equal(const char *a, const char *b)
{
    while (*a && *b) {
        int ca = (*a >= 'A' && *a <= 'Z') ? *a + 32 : *a;
        int cb = (*b >= 'A' && *b <= 'Z') ? *b + 32 : *b;
        if (ca != cb)
            return 0;
        a++;
        b++;
    }
    return *a == *b;
}

/* Accepts the backend name from the game list ("fifteen") and the
 * display name from a save file ("Fifteen"). */
static const game *find_game(const char *name)
{
    int i;

    for (i = 0; i < gamecount; i++)
        if (!strcmp(gamenames[i], name) || ci_equal(gamelist[i]->name, name))
            return gamelist[i];
    return NULL;
}

static void free_frontend(frontend *fe)
{
    if (fe->me) {
        midend_free(fe->me);
        fe->me = NULL;
    }
}

/* Gives the drawstate a size, so that coordinate input works. The
 * result depends only on the game and the parameters, so tests can
 * compute click positions from the reported width and height. */
static void size_frontend(frontend *fe)
{
    /* A large bounding box, so midend_size returns the game's
     * preferred size instead of the smallest legal size. */
    fe->width = fe->height = 4096;
    midend_size(fe->me, &fe->width, &fe->height, false, 1.0);
}

static void put_state_fields(frontend *fe)
{
    sbuf save = {0};
    char *id;

    midend_serialise(fe->me, sbuf_write_cb, &save);
    if (!save.buf)
        sbuf_add(&save, "", 0);
    id = midend_get_game_id(fe->me);

    fputs("\"state\":", stdout);
    put_json_string(save.buf);
    fputs(",\"id\":", stdout);
    put_json_string(id);
    fputs(",\"game\":", stdout);
    put_json_string(midend_which_game(fe->me)->name);
    printf(",\"w\":%d,\"h\":%d,\"canundo\":%s,\"canredo\":%s,\"status\":%d",
           fe->width, fe->height,
           midend_can_undo(fe->me) ? "true" : "false",
           midend_can_redo(fe->me) ? "true" : "false",
           midend_status(fe->me));

    sfree(id);
    sfree(save.buf);
}

static void put_state(frontend *fe)
{
    fputs("{\"ok\":true,", stdout);
    put_state_fields(fe);
    fputs("}\n", stdout);
}

/* Opens a midend from a serialised save file. Returns NULL on success
 * and an error message on failure. The drawing API and its handle are
 * chosen by the caller: the state operations use the no-op API, and a
 * trace uses the recording API. */
static const char *open_state_with_unsized(frontend *fe, const char *save,
                                           const drawing_api *api, void *handle,
                                           bool size_it)
{
    rbuf rd;
    char *name = NULL;
    const game *g;
    const char *error;

    rd.buf = save;
    rd.len = (int)strlen(save);
    rd.pos = 0;

    error = identify_game(&name, rbuf_read_cb, &rd);
    if (error)
        return error;

    g = find_game(name);
    sfree(name);
    if (!g)
        return "save file names an unknown game";

    fe->me = midend_new(fe, g, api, handle);
    rd.pos = 0;
    error = midend_deserialise(fe->me, rbuf_read_cb, &rd);
    if (error) {
        free_frontend(fe);
        return error;
    }
    if (size_it)
        size_frontend(fe);
    return NULL;
}

static const char *open_state_with(frontend *fe, const char *save,
                                   const drawing_api *api, void *handle)
{
    return open_state_with_unsized(fe, save, api, handle, true);
}

static const char *open_state(frontend *fe, const char *save)
{
    return open_state_with(fe, save, &oracle_drawing, NULL);
}

/* ------------------------------------------------------------------ */
/* Operations.                                                        */
/* ------------------------------------------------------------------ */

static void do_games(void)
{
    int i;

    fputs("{\"ok\":true,\"games\":[", stdout);
    for (i = 0; i < gamecount; i++) {
        if (i)
            putchar(',');
        put_json_string(gamenames[i]);
    }
    fputs("]}\n", stdout);
}

/* Emits one JSON object per preset, depth first. Menus may nest, so
 * the helper walks submenus and emits only entries that hold params. */
static void put_preset_entries(const game *g, struct preset_menu *menu,
                               bool *first)
{
    int i;

    if (!menu)
        return;

    for (i = 0; i < menu->n_entries; i++) {
        struct preset_menu_entry *entry = &menu->entries[i];

        if (entry->submenu) {
            put_preset_entries(g, entry->submenu, first);
        } else if (entry->params) {
            char *encoded = g->encode_params(entry->params, true);

            if (!*first)
                putchar(',');
            *first = false;
            fputs("{\"title\":", stdout);
            put_json_string(entry->title);
            fputs(",\"params\":", stdout);
            put_json_string(encoded);
            putchar('}');
            sfree(encoded);
        }
    }
}

static void do_presets(const char *gamename)
{
    const game *g = find_game(gamename);
    frontend fe = {0};
    game_params *params;
    struct preset_menu *menu;
    int id_limit = 0;

    if (!g) {
        put_error("unknown game");
        return;
    }

    fe.me = midend_new(&fe, g, &oracle_drawing, NULL);
    params = g->default_params();
    midend_set_params(fe.me, params);
    g->free_params(params);
    midend_new_game(fe.me);
    menu = midend_get_presets(fe.me, &id_limit);

    fputs("{\"ok\":true,\"presets\":[", stdout);
    if (menu) {
        bool first = true;
        put_preset_entries(g, menu, &first);
    }
    fputs("]}\n", stdout);

    free_frontend(&fe);
}

/* Creates a new game in `fe`. Returns NULL on success and an error
 * message on failure. The caller owns `fe`. */
static const char *make_game(frontend *fe, const char *gamename,
                             const char *params, const char *seed,
                             const drawing_api *api, void *handle)
{
    const game *g = find_game(gamename);
    char *id;
    const char *error;

    if (!g)
        return "unknown game";
    if (!seed)
        return "new needs a seed";

    fe->me = midend_new(fe, g, api, handle);

    /*
     * Always pass "params#seed" in DEF_PARAMS mode. With an empty
     * parameter string, encode the game's own defaults: the DEF_SEED
     * path relies on the frontend having set curparams first, which a
     * stateless oracle does not do.
     */
    {
        char *par;
        if (params && *params) {
            par = dupstr(params);
        } else {
            game_params *def = g->default_params();
            par = g->encode_params(def, false);
            g->free_params(def);
        }
        id = snewn(strlen(par) + strlen(seed) + 2, char);
        sprintf(id, "%s#%s", par, seed);
        sfree(par);
    }
    error = midend_game_id_int(fe->me, id, DEF_PARAMS, false);
    sfree(id);
    if (error) {
        free_frontend(fe);
        return error;
    }
    midend_new_game(fe->me);
    size_frontend(fe);
    return NULL;
}

static void do_new(const char *gamename, const char *params,
                   const char *seed)
{
    frontend fe = {0};
    const char *error = make_game(&fe, gamename, params, seed,
                                  &oracle_drawing, NULL);

    if (error) {
        put_error(error);
        return;
    }
    put_state(&fe);
    free_frontend(&fe);
}

/*
 * Replays a space- or comma-separated list of button codes inside one
 * midend, with the pointer at 0,0. Cursor games keep the cursor in
 * game_ui, which is not part of the save file, so a sequence must run in
 * one midend. Returns NULL on success.
 */
static const char *process_key_seq(frontend *fe, const char *seq)
{
    const char *p = seq;

    while (*p) {
        char *end;
        long key;

        while (*p == ' ' || *p == ',' || *p == '\t')
            p++;
        if (!*p)
            break;
        key = strtol(p, &end, 10);
        if (end == p)
            return "keys needs a space- or comma-separated list";
        p = end;
        midend_process_key(fe->me, 0, 0, (int)key);
    }
    return NULL;
}

/*
 * Applies preferences in the upstream preferences file format: one
 * `kw=value` per line, with space-stuffed continuations. Returns NULL on
 * success. The parser is upstream's, so unknown keywords are ignored and
 * an invalid value is an error, as in the app.
 */
static const char *apply_prefs(frontend *fe, const char *prefs)
{
    rbuf rd;

    if (!prefs)
        return NULL;
    rd.buf = prefs;
    rd.len = (int)strlen(prefs);
    rd.pos = 0;
    return midend_load_prefs(fe->me, rbuf_read_cb, &rd);
}

static void put_trace_body(frontend *fe, trace_ctx *trace)
{
    float *colours;
    char *id;
    char *params;
    int ncolours = 0, i;

    /*
     * The caller has already run one redraw into `trace`. A first
     * redraw of a new drawstate clears the window with colour 0, and
     * that clear is part of the contract.
     */
    colours = midend_colours(fe->me, &ncolours);
    id = midend_get_game_id(fe->me);
    params = midend_get_current_params(fe->me, true);

    fputs("{\"traceSchema\":1,\"game\":", stdout);
    put_json_string(midend_which_game(fe->me)->name);
    fputs(",\"id\":", stdout);
    put_json_string(id);
    fputs(",\"params\":", stdout);
    put_json_string(params);
    printf(",\"w\":%d,\"h\":%d,\"palette\":[", fe->width, fe->height);
    for (i = 0; i < ncolours; i++)
        printf("%s[%ld,%ld,%ld]", i ? "," : "",
               lroundf(colours[i * 3 + 0] * 1000.0F),
               lroundf(colours[i * 3 + 1] * 1000.0F),
               lroundf(colours[i * 3 + 2] * 1000.0F));
    fputs("],\"ops\":[", stdout);
    if (trace->ops.buf)
        fputs(trace->ops.buf, stdout);
    fputs("]}", stdout);

    sfree(colours);
    sfree(id);
    sfree(params);
}

static void put_trace(frontend *fe, trace_ctx *trace)
{
    fputs("{\"ok\":true,\"trace\":", stdout);
    put_trace_body(fe, trace);
    fputs("}\n", stdout);
}

static void do_trace(const jobject *req)
{
    const char *save = field_string(req, "state");
    const char *seq = field_string(req, "seq");
    const char *prefs = field_string(req, "prefs");
    frontend fe = {0};
    trace_ctx trace = {0};
    const char *error;

    if (!save) {
        put_error("missing state");
        return;
    }
    error = open_state_with(&fe, save, &recording_drawing, &trace);
    if (error) {
        put_error(error);
        return;
    }

    error = apply_prefs(&fe, prefs);
    if (error)
        goto fail;

    if (seq) {
        error = process_key_seq(&fe, seq);
        if (error)
            goto fail;

        /*
         * A cursor key only changes game_ui, and processing it redraws
         * into the trace. Drop those operations and start a fresh
         * drawstate, so the response has the shape of a first redraw.
         * The old drawstate may own blitters, and freeing it appends
         * blitter_free operations. Suppress them: they describe a
         * drawstate that is not in the trace. The first start_draw of
         * the replacement drawstate clears the flag.
         */
        trace.ops.len = 0;
        if (trace.ops.buf)
            trace.ops.buf[0] = '\0';
        trace.nops = 0;
        trace.blitters = 0;
        trace.suppress_blitter_free = true;
        midend_force_redraw(fe.me);
    } else {
        midend_redraw(fe.me);
    }

    put_trace(&fe, &trace);

    /* Freeing the midend frees its blitters, which appends to the trace
     * buffer. The frontend must go before the buffer. */
    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
    return;

  fail:
    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
    put_error(error);
}

static void do_trace_new(const char *gamename, const char *params,
                         const char *seed)
{
    frontend fe = {0};
    trace_ctx trace = {0};
    const char *error = make_game(&fe, gamename, params, seed,
                                  &recording_drawing, &trace);

    if (error) {
        put_error(error);
        return;
    }

    midend_redraw(fe.me);
    put_trace(&fe, &trace);

    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
}

static void do_key_seq(const char *save, const char *seq, const char *prefs)
{
    frontend fe = {0};
    const char *error;

    if (!save) {
        put_error("missing state");
        return;
    }
    if (!seq) {
        put_error("keys needs a sequence");
        return;
    }

    error = open_state(&fe, save);
    if (error) {
        put_error(error);
        return;
    }

    error = apply_prefs(&fe, prefs);
    if (error) {
        free_frontend(&fe);
        put_error(error);
        return;
    }

    error = process_key_seq(&fe, seq);
    if (error) {
        free_frontend(&fe);
        put_error(error);
        return;
    }

    put_state(&fe);
    free_frontend(&fe);
}

/* ------------------------------------------------------------------ */
/* Disjoint-set forest and to-do queue.                               */
/* ------------------------------------------------------------------ */

static bool parse_next_long(const char **p, long *out)
{
    char *end;

    while (**p == ' ' || **p == '\t' || **p == ',')
        (*p)++;
    if (!**p)
        return false;
    *out = strtol(*p, &end, 10);
    if (end == *p)
        return false;
    *p = end;
    return true;
}

static void do_dsf(const jobject *req)
{
    const char *kind = field_string(req, "kind");
    const char *ops = field_string(req, "ops");
    long n = 0;
    DSF *dsf = NULL;
    const char *p;
    int i;

    if (!kind || !field_int(req, "n", &n) || n < 1 || n > 100000) {
        put_error("dsf needs a kind and 1 <= n <= 100000");
        return;
    }
    if (!strcmp(kind, "normal"))
        dsf = dsf_new((int)n);
    else if (!strcmp(kind, "flip"))
        dsf = dsf_new_flip((int)n);
    else if (!strcmp(kind, "min"))
        dsf = dsf_new_min((int)n);
    else {
        put_error("dsf kind must be normal, flip, or min");
        return;
    }

    p = ops ? ops : "";
    while (*p) {
        char op;
        long a, b, inverse = 0;

        while (*p == ' ' || *p == '\t' || *p == ',')
            p++;
        if (!*p)
            break;
        op = *p++;
        if (op == 'm') {
            if (!parse_next_long(&p, &a) || !parse_next_long(&p, &b) ||
                a < 0 || b < 0 || a >= n || b >= n) {
                dsf_free(dsf);
                put_error("dsf merge needs two valid indices");
                return;
            }
            dsf_merge(dsf, (int)a, (int)b);
        } else if (op == 'f') {
            if (!parse_next_long(&p, &a) || !parse_next_long(&p, &b) ||
                !parse_next_long(&p, &inverse) ||
                a < 0 || b < 0 || a >= n || b >= n) {
                dsf_free(dsf);
                put_error("dsf flip merge needs two indices and a flag");
                return;
            }
            dsf_merge_flip(dsf, (int)a, (int)b, inverse != 0);
        } else {
            dsf_free(dsf);
            put_error("dsf ops support m and f");
            return;
        }
    }

    fputs("{\"ok\":true,\"roots\":[", stdout);
    for (i = 0; i < n; i++) {
        int root;
        if (!strcmp(kind, "flip")) {
            bool inverse;
            root = dsf_canonify_flip(dsf, i, &inverse);
        } else {
            root = dsf_canonify(dsf, i);
        }
        printf("%s%d", i ? "," : "", root);
    }
    fputs("],\"inverses\":[", stdout);
    for (i = 0; i < n; i++) {
        if (!strcmp(kind, "flip")) {
            bool inverse;
            dsf_canonify_flip(dsf, i, &inverse);
            printf("%s%d", i ? "," : "", inverse ? 1 : 0);
        } else {
            printf("%s0", i ? "," : "");
        }
    }
    /*
     * dsf_size uses the non-flip path compression. On a flip dsf that
     * makes later flip queries meaningless, so this comes after all
     * canonify_flip calls.
     */
    fputs("],\"sizes\":[", stdout);
    for (i = 0; i < n; i++)
        printf("%s%d", i ? "," : "", dsf_size(dsf, i));
    fputs("],\"mins\":[", stdout);
    for (i = 0; i < n; i++) {
        if (!strcmp(kind, "min"))
            printf("%s%d", i ? "," : "", dsf_minimal(dsf, i));
        else
            printf("%s-1", i ? "," : "");
    }
    fputs("]}\n", stdout);
    dsf_free(dsf);
}

/*
 * divvy_rectangle. A request carries w, h, k and either a seed or an
 * encoded state. The response carries the canonical element and the
 * class size of every cell, the number of attempts that divvy_rectangle
 * needed, and the encoded state after the call. The state proves that
 * the shuffle and the per-iteration draws consume the same stream as
 * the Kotlin port.
 *
 * This mirrors divvy_rectangle (divvy.c:658-667): divvy_rectangle_attempt
 * runs again after a failure, and every attempt redraws the permutation.
 * The attempt cap is an oracle safety net only. A vector that reaches it
 * would hang the test process, so the oracle reports an error instead.
 */
#define DIVVY_MAX_ATTEMPTS 1000
#define DIVVY_MAX_CELLS 1000000

static void do_divvy(const jobject *req)
{
    const char *seed = field_string(req, "seed");
    const char *state = field_string(req, "state");
    long w = 0, h = 0, k = 0;
    random_state *rs;
    DSF *dsf = NULL;
    char *encoded;
    int wh, attempts = 0, i;

    if (!field_int(req, "w", &w) || !field_int(req, "h", &h) ||
        !field_int(req, "k", &k)) {
        put_error("divvy needs w, h, k");
        return;
    }
    if (w < 1 || h < 1 || k < 1) {
        put_error("divvy needs positive w, h, k");
        return;
    }
    if (w > DIVVY_MAX_CELLS / h) {
        put_error("divvy needs w*h <= 1000000");
        return;
    }
    wh = (int)(w * h);
    if (wh % k != 0) {
        put_error("divvy needs k to divide w*h");
        return;
    }

    if (state)
        rs = random_state_decode(state);
    else if (seed)
        rs = random_new(seed, (int)strlen(seed));
    else {
        put_error("divvy needs a seed or a state");
        return;
    }

    do {
        dsf = divvy_rectangle_attempt((int)w, (int)h, (int)k, rs);
        attempts++;
    } while (!dsf && attempts < DIVVY_MAX_ATTEMPTS);

    if (!dsf) {
        random_free(rs);
        put_error("divvy did not find a division within 1000 attempts");
        return;
    }

    printf("{\"ok\":true,\"attempts\":%d,\"roots\":[", attempts);
    for (i = 0; i < wh; i++)
        printf("%s%d", i ? "," : "", dsf_canonify(dsf, i));
    fputs("],\"sizes\":[", stdout);
    for (i = 0; i < wh; i++)
        printf("%s%d", i ? "," : "", dsf_size(dsf, i));
    encoded = random_state_encode(rs);
    fputs("],\"state\":", stdout);
    put_json_string(encoded);
    fputs("}\n", stdout);

    sfree(encoded);
    dsf_free(dsf);
    random_free(rs);
}

/*
 * domino_layout from laydomino.c. A request carries w, h and either a
 * seed or an encoded state. The response carries the layout, the same
 * layout from domino_layout_prealloc with a fresh state from the same
 * seed, the number of layout attempts, and the encoded state after each
 * call.
 *
 * laydomino has no retry and no acceptance test: one call always
 * returns a grid, so the attempt count is always one. The field exists
 * so the parity test compares the same response shape as divvy, and so
 * a future caller that does retry cannot silently reuse this shape.
 */
#define LAYDOMINO_MAX_CELLS 1000000
#define LAYDOMINO_ATTEMPTS 1

static void do_laydomino(const jobject *req)
{
    const char *seed = field_string(req, "seed");
    const char *state = field_string(req, "state");
    long w = 0, h = 0;
    random_state *rs;
    int *grid = NULL, *grid2 = NULL, *list = NULL, *prealloc = NULL;
    char *encoded = NULL, *prealloc_encoded = NULL;
    int wh, i;

    if (!field_int(req, "w", &w) || !field_int(req, "h", &h)) {
        put_error("laydomino needs w and h");
        return;
    }
    if (w < 1 || h < 1) {
        put_error("laydomino needs positive w and h");
        return;
    }
    if (w > LAYDOMINO_MAX_CELLS / h) {
        put_error("laydomino needs w*h <= 1000000");
        return;
    }
    if (!state && !seed) {
        put_error("laydomino needs a seed or a state");
        return;
    }

    wh = (int)(w * h);

    if (state)
        rs = random_state_decode(state);
    else
        rs = random_new(seed, (int)strlen(seed));
    grid = domino_layout((int)w, (int)h, rs);
    encoded = random_state_encode(rs);
    random_free(rs);

    /*
     * The prealloc entry point must agree with the allocating one. A
     * second call from the same seed also proves that both paths draw
     * the same stream.
     */
    if (state)
        rs = random_state_decode(state);
    else
        rs = random_new(seed, (int)strlen(seed));
    prealloc = snewn(wh, int);
    grid2 = snewn(wh, int);
    list = snewn(2 * wh, int);
    domino_layout_prealloc((int)w, (int)h, rs, prealloc, grid2, list);
    prealloc_encoded = random_state_encode(rs);
    random_free(rs);

    printf("{\"ok\":true,\"attempts\":%d,\"grid\":[", LAYDOMINO_ATTEMPTS);
    for (i = 0; i < wh; i++)
        printf("%s%d", i ? "," : "", grid[i]);
    fputs("],\"preallocGrid\":[", stdout);
    for (i = 0; i < wh; i++)
        printf("%s%d", i ? "," : "", prealloc[i]);
    fputs("],\"state\":", stdout);
    put_json_string(encoded);
    fputs(",\"preallocState\":", stdout);
    put_json_string(prealloc_encoded);
    fputs("}\n", stdout);

    sfree(grid);
    sfree(grid2);
    sfree(list);
    sfree(prealloc);
    sfree(encoded);
    sfree(prealloc_encoded);
}

/*
 * latin.c harness. A request carries a kind.
 *
 *   generate and generateRect run latin_generate and
 *   latin_generate_rect from a seed or an encoded state, and return the
 *   flat grid and the encoded state after the call. The state proves
 *   that the row shuffle and the matching subroutine consume the same
 *   stream as the Kotlin port.
 *
 *   solve runs the generic latin_solver with the five difficulty levels
 *   equal to 0..4, no user solvers, and no validator, so it describes
 *   the shared solver alone. It returns the difficulty and the grid
 *   after the call, including the partial or first-solution grid that
 *   the C code leaves behind.
 *
 *   check runs latin_check and reports whether the grid is a Latin
 *   square, in the sense that latin_check accepts.
 *
 * The solver asserts on digits outside 1..order, so every request is
 * validated before the call. A bad request is an error, never a crash.
 */
#define LATIN_MAX_ORDER 64

static bool parse_latin_grid(const char *text, digit *grid, int cells, int order)
{
    const char *p = text ? text : "";
    int i;

    for (i = 0; i < cells; i++) {
        long value;

        if (!parse_next_long(&p, &value) || value < 0 || value > order)
            return false;
        grid[i] = (digit)value;
    }
    while (*p == ' ' || *p == '\t' || *p == ',')
        p++;
    return *p == '\0';
}

/* ------------------------------------------------------------------ */
/* Grids.                                                             */
/* ------------------------------------------------------------------ */

static const char *const grid_type_names[] = {
#define GRID_NAME(upper, lower) #lower,
    GRIDGEN_LIST(GRID_NAME)
#undef GRID_NAME
};

static bool parse_grid_type(const char *name, grid_type *out)
{
    int i;

    if (!name)
        return false;
    for (i = 0; i < GRID_TYPE_MAX; i++) {
        if (!strcmp(name, grid_type_names[i])) {
            *out = (grid_type)i;
            return true;
        }
    }
    return false;
}

/*
 * Builds a grid for the grid operation. Returns NULL after putting an
 * error response when the request or the description is invalid.
 */
static grid *grid_from_request(const jobject *req)
{
    const char *type_name = field_string(req, "type");
    const char *desc = field_string(req, "desc");
    const char *error;
    long w = 0, h = 0;
    grid_type type;
    grid *g;

    if (!parse_grid_type(type_name, &type)) {
        put_error("grid needs a known type");
        return NULL;
    }
    if (!field_int(req, "w", &w) || !field_int(req, "h", &h) ||
        w < 1 || h < 1 || w > 1000 || h > 1000) {
        put_error("grid needs 1 <= w, h <= 1000");
        return NULL;
    }

    error = grid_validate_params(type, (int)w, (int)h);
    if (error) {
        put_error(error);
        return NULL;
    }
    error = grid_validate_desc(type, (int)w, (int)h, desc);
    if (error) {
        put_error(error);
        return NULL;
    }

    g = grid_new(type, (int)w, (int)h, desc);
    if (!g) {
        put_error("grid_new returned no grid");
        return NULL;
    }
    return g;
}

static void do_grid(const jobject *req)
{
    const char *kind = field_string(req, "kind");
    const char *type_name = field_string(req, "type");
    const char *seed = field_string(req, "seed");
    const char *state = field_string(req, "state");
    long w = 0, h = 0, x = 0, y = 0, want_incentre = 0;
    grid_type type;
    grid *g;
    int i, j;

    if (!kind) {
        put_error("grid needs a kind");
        return;
    }
    if (!parse_grid_type(type_name, &type)) {
        put_error("grid needs a known type");
        return;
    }

    if (!strcmp(kind, "desc")) {
        random_state *rs;
        char *desc;
        char *encoded;

        if (!field_int(req, "w", &w) || !field_int(req, "h", &h) ||
            w < 1 || h < 1 || w > 1000 || h > 1000) {
            put_error("grid desc needs 1 <= w, h <= 1000");
            return;
        }
        if (state)
            rs = random_state_decode(state);
        else if (seed)
            rs = random_new(seed, (int)strlen(seed));
        else {
            put_error("grid desc needs a seed or a state");
            return;
        }

        desc = grid_new_desc(type, (int)w, (int)h, rs);
        encoded = random_state_encode(rs);
        /* An absent description is an empty string: the oracle protocol
         * has no null, and no grid type uses an empty description. */
        fputs("{\"ok\":true,\"desc\":", stdout);
        put_json_string(desc ? desc : "");
        fputs(",\"state\":", stdout);
        put_json_string(encoded);
        fputs("}\n", stdout);

        sfree(desc);
        sfree(encoded);
        random_free(rs);
        return;
    }

    if (!strcmp(kind, "validate")) {
        const char *error;

        if (!field_int(req, "w", &w) || !field_int(req, "h", &h) ||
            w < 1 || h < 1) {
            put_error("grid validate needs positive w and h");
            return;
        }
        error = grid_validate_desc(type, (int)w, (int)h,
                                   field_string(req, "desc"));
        /* No error is an empty string: the oracle protocol has no null,
         * and every error message is non-empty. */
        fputs("{\"ok\":true,\"error\":", stdout);
        put_json_string(error ? error : "");
        fputs("}\n", stdout);
        return;
    }

    if (!strcmp(kind, "size")) {
        int tilesize, xextent, yextent;
        const char *error;

        if (!field_int(req, "w", &w) || !field_int(req, "h", &h) ||
            w < 1 || h < 1) {
            put_error("grid size needs positive w and h");
            return;
        }
        error = grid_validate_params(type, (int)w, (int)h);
        if (error) {
            put_error(error);
            return;
        }
        grid_compute_size(type, (int)w, (int)h, &tilesize, &xextent, &yextent);
        printf("{\"ok\":true,\"tilesize\":%d,\"xextent\":%d,\"yextent\":%d}\n",
               tilesize, xextent, yextent);
        return;
    }

    if (!strcmp(kind, "nearest")) {
        grid_edge *edge;

        if (!field_int(req, "x", &x) || !field_int(req, "y", &y)) {
            put_error("grid nearest needs x and y");
            return;
        }
        g = grid_from_request(req);
        if (!g)
            return;
        edge = grid_nearest_edge(g, (int)x, (int)y);
        printf("{\"ok\":true,\"edge\":%d}\n", edge ? edge->index : -1);
        grid_free(g);
        return;
    }

    if (!strcmp(kind, "new")) {
        g = grid_from_request(req);
        if (!g)
            return;

        /* Computing every incentre is optional: it is a separate step
         * in the C API, and it costs time on large grids. */
        if (field_int(req, "incentre", &want_incentre) && want_incentre) {
            for (i = 0; i < g->num_faces; i++)
                grid_find_incentre(g->faces[i]);
        }

        printf("{\"ok\":true,\"lowestX\":%d,\"lowestY\":%d,"
               "\"highestX\":%d,\"highestY\":%d,\"tilesize\":%d,\"dots\":[",
               g->lowest_x, g->lowest_y, g->highest_x, g->highest_y,
               g->tilesize);
        for (i = 0; i < g->num_dots; i++) {
            grid_dot *d = g->dots[i];
            printf("%s{\"i\":%d,\"x\":%d,\"y\":%d,\"edges\":[",
                   i ? "," : "", d->index, d->x, d->y);
            for (j = 0; j < d->order; j++)
                printf("%s%d", j ? "," : "", d->edges[j] ? d->edges[j]->index : -1);
            fputs("],\"faces\":[", stdout);
            for (j = 0; j < d->order; j++)
                printf("%s%d", j ? "," : "", d->faces[j] ? d->faces[j]->index : -1);
            fputs("]}", stdout);
        }
        fputs("],\"edges\":[", stdout);
        for (i = 0; i < g->num_edges; i++) {
            grid_edge *e = g->edges[i];
            printf("%s{\"i\":%d,\"dot1\":%d,\"dot2\":%d,\"face1\":%d,\"face2\":%d}",
                   i ? "," : "", e->index, e->dot1->index, e->dot2->index,
                   e->face1 ? e->face1->index : -1,
                   e->face2 ? e->face2->index : -1);
        }
        fputs("],\"faces\":[", stdout);
        for (i = 0; i < g->num_faces; i++) {
            grid_face *f = g->faces[i];
            printf("%s{\"i\":%d,\"order\":%d,\"edges\":[",
                   i ? "," : "", f->index, f->order);
            for (j = 0; j < f->order; j++)
                printf("%s%d", j ? "," : "", f->edges[j] ? f->edges[j]->index : -1);
            fputs("],\"dots\":[", stdout);
            for (j = 0; j < f->order; j++)
                printf("%s%d", j ? "," : "", f->dots[j] ? f->dots[j]->index : -1);
            printf("],\"hasIncentre\":%s",
                   f->has_incentre ? "true" : "false");
            if (f->has_incentre)
                printf(",\"ix\":%d,\"iy\":%d", f->ix, f->iy);
            fputs("}", stdout);
        }
        fputs("]}\n", stdout);
        grid_free(g);
        return;
    }

    put_error("unknown grid kind");
}

static void do_latin(const jobject *req)
{
    const char *kind = field_string(req, "kind");
    const char *seed = field_string(req, "seed");
    const char *state = field_string(req, "state");
    long o = 0, w = 0, h = 0, maxdiff = 0;
    random_state *rs = NULL;
    digit *grid = NULL;
    char *encoded = NULL;
    int cells = 0, i;

    if (!kind) {
        put_error("latin needs a kind");
        return;
    }

    if (!strcmp(kind, "generate") || !strcmp(kind, "generateRect")) {
        if (!strcmp(kind, "generate")) {
            if (!field_int(req, "o", &o) || o < 1 || o > LATIN_MAX_ORDER) {
                put_error("latin generate needs 1 <= o <= 64");
                return;
            }
            w = h = o;
        } else {
            if (!field_int(req, "w", &w) || !field_int(req, "h", &h) ||
                w < 1 || h < 1 || w > LATIN_MAX_ORDER || h > LATIN_MAX_ORDER) {
                put_error("latin generateRect needs 1 <= w, h <= 64");
                return;
            }
        }
        if (state)
            rs = random_state_decode(state);
        else if (seed)
            rs = random_new(seed, (int)strlen(seed));
        else {
            put_error("latin needs a seed or a state");
            return;
        }

        cells = (int)(w * h);
        if (!strcmp(kind, "generate"))
            grid = latin_generate((int)w, rs);
        else
            grid = latin_generate_rect((int)w, (int)h, rs);
        encoded = random_state_encode(rs);

        printf("{\"ok\":true,\"kind\":\"%s\",\"order\":%d,\"w\":%d,\"h\":%d,\"grid\":[",
               kind, (int)(w > h ? w : h), (int)w, (int)h);
        for (i = 0; i < cells; i++)
            printf("%s%d", i ? "," : "", grid[i]);
        fputs("],\"state\":", stdout);
        put_json_string(encoded);
        fputs("}\n", stdout);

        sfree(grid);
        sfree(encoded);
        random_free(rs);
        return;
    }

    if (!strcmp(kind, "solve")) {
        usersolver_t solvers[5] = { NULL, NULL, NULL, NULL, NULL };
        int diff;

        if (!field_int(req, "o", &o) || o < 1 || o > LATIN_MAX_ORDER) {
            put_error("latin solve needs 1 <= o <= 64");
            return;
        }
        if (!field_int(req, "maxdiff", &maxdiff) || maxdiff < 0 || maxdiff > 4) {
            put_error("latin solve needs 0 <= maxdiff <= 4");
            return;
        }
        cells = (int)(o * o);
        grid = snewn(cells, digit);
        if (!parse_latin_grid(field_string(req, "grid"), grid, cells, (int)o)) {
            sfree(grid);
            put_error("latin grid needs o*o digits in 0..o");
            return;
        }

        diff = latin_solver(grid, (int)o, (int)maxdiff,
                            0, 1, 2, 3, 4,
                            solvers, NULL, NULL, NULL, NULL);

        printf("{\"ok\":true,\"kind\":\"solve\",\"order\":%d,\"diff\":%d,\"grid\":[",
               (int)o, diff);
        for (i = 0; i < cells; i++)
            printf("%s%d", i ? "," : "", grid[i]);
        fputs("]}\n", stdout);

        sfree(grid);
        return;
    }

    if (!strcmp(kind, "check")) {
        bool invalid;

        if (!field_int(req, "o", &o) || o < 1 || o > LATIN_MAX_ORDER) {
            put_error("latin check needs 1 <= o <= 64");
            return;
        }
        cells = (int)(o * o);
        grid = snewn(cells, digit);
        if (!parse_latin_grid(field_string(req, "grid"), grid, cells, (int)o)) {
            sfree(grid);
            put_error("latin grid needs o*o digits in 0..o");
            return;
        }

        invalid = latin_check(grid, (int)o);
        printf("{\"ok\":true,\"kind\":\"check\",\"order\":%d,\"isLatin\":%s}\n",
               (int)o, invalid ? "false" : "true");

        sfree(grid);
        return;
    }

    put_error("latin kind must be generate, generateRect, solve, or check");
}

static void do_tdq(const jobject *req)
{
    const char *ops = field_string(req, "ops");
    long n = 0;
    tdq *queue;
    const char *p;
    int *removed = NULL;
    int removed_count = 0, removed_capacity = 0;
    int i;

    if (!field_int(req, "n", &n) || n < 1 || n > 1000000) {
        put_error("tdq needs 1 <= n <= 1000000");
        return;
    }
    queue = tdq_new((int)n);

    p = ops ? ops : "";
    while (*p) {
        char op;
        long k;

        while (*p == ' ' || *p == '\t' || *p == ',')
            p++;
        if (!*p)
            break;
        op = *p++;
        if (op == 'a') {
            if (!parse_next_long(&p, &k) || k < 0 || k >= n) {
                sfree(removed);
                tdq_free(queue);
                put_error("tdq add needs a valid index");
                return;
            }
            tdq_add(queue, (int)k);
        } else if (op == 'r') {
            int value = tdq_remove(queue);
            if (removed_count == removed_capacity) {
                removed_capacity = removed_capacity ? removed_capacity * 2 : 32;
                removed = sresize(removed, removed_capacity, int);
            }
            removed[removed_count++] = value;
        } else if (op == 'f') {
            tdq_fill(queue);
        } else {
            sfree(removed);
            tdq_free(queue);
            put_error("tdq ops support a, r, and f");
            return;
        }
    }

    for (;;) {
        int value = tdq_remove(queue);
        if (value < 0)
            break;
        if (removed_count == removed_capacity) {
            removed_capacity = removed_capacity ? removed_capacity * 2 : 32;
            removed = sresize(removed, removed_capacity, int);
        }
        removed[removed_count++] = value;
    }

    fputs("{\"ok\":true,\"removed\":[", stdout);
    for (i = 0; i < removed_count; i++)
        printf("%s%d", i ? "," : "", removed[i]);
    fputs("]}\n", stdout);
    sfree(removed);
    tdq_free(queue);
}

/* ------------------------------------------------------------------ */
/* tree234.c harness.                                                 */
/* ------------------------------------------------------------------ */

struct tree_elem {
    int key;
    int id;
};

static int tree_compare(void *av, void *bv)
{
    const struct tree_elem *a = av, *b = bv;
    return (a->key < b->key) ? -1 : (a->key > b->key) ? 1 : 0;
}

static void do_tree(const jobject *req)
{
    const char *ops = field_string(req, "ops");
    tree234 *tree = newtree234(tree_compare);
    struct tree_elem **allocated = NULL;
    long *results = NULL;
    int alloc_count = 0, alloc_capacity = 0;
    int result_count = 0, result_capacity = 0;
    const char *p = ops ? ops : "";
    int i;

    while (*p) {
        char op;
        long a = 0, b = 0, result = -1;
        struct tree_elem *elem, *found;

        while (*p == ' ' || *p == '\t' || *p == ',')
            p++;
        if (!*p)
            break;
        op = *p++;
        if (op == 'i') {
            if (!parse_next_long(&p, &a)) {
                put_error("tree index needs an index");
                goto fail;
            }
            found = index234(tree, (int)a);
            result = found ? found->id : -1;
        } else {
            if (!parse_next_long(&p, &a)) {
                put_error("tree op needs a key");
                goto fail;
            }
            if (op == 'a') {
                if (!parse_next_long(&p, &b)) {
                    put_error("tree add needs an id");
                    goto fail;
                }
                elem = snew(struct tree_elem);
                elem->key = (int)a;
                elem->id = (int)b;
                if (alloc_count == alloc_capacity) {
                    alloc_capacity = alloc_capacity ? alloc_capacity * 2 : 64;
                    allocated = sresize(allocated, alloc_capacity, struct tree_elem *);
                }
                allocated[alloc_count++] = elem;
                found = add234(tree, elem);
                result = found->id;
            } else {
                elem = snew(struct tree_elem);
                elem->key = (int)a;
                elem->id = -1;
                if (op == 'd') {
                    found = del234(tree, elem);
                    result = found ? found->id : -1;
                } else if (op == 'f') {
                    found = find234(tree, elem, NULL);
                    result = found ? found->id : -1;
                } else if (op == 't') {
                    found = findrel234(tree, elem, NULL, REL234_LT);
                    result = found ? found->id : -1;
                } else if (op == 'e') {
                    found = findrel234(tree, elem, NULL, REL234_LE);
                    result = found ? found->id : -1;
                } else if (op == 'g') {
                    found = findrel234(tree, elem, NULL, REL234_GT);
                    result = found ? found->id : -1;
                } else if (op == 'h') {
                    found = findrel234(tree, elem, NULL, REL234_GE);
                    result = found ? found->id : -1;
                } else {
                    sfree(elem);
                    put_error("tree ops support a, d, f, t, e, g, h, i");
                    goto fail;
                }
                sfree(elem);
            }
        }
        if (result_count == result_capacity) {
            result_capacity = result_capacity ? result_capacity * 2 : 64;
            results = sresize(results, result_capacity, long);
        }
        results[result_count++] = result;
    }

    fputs("{\"ok\":true,\"results\":[", stdout);
    for (i = 0; i < result_count; i++)
        printf("%s%ld", i ? "," : "", results[i]);
    fputs("],\"items\":[", stdout);
    for (i = 0;; i++) {
        struct tree_elem *elem = index234(tree, i);
        if (!elem)
            break;
        printf("%s\"%d:%d\"", i ? "," : "", elem->key, elem->id);
    }
    fputs("]}\n", stdout);

    sfree(results);
    for (i = 0; i < alloc_count; i++)
        sfree(allocated[i]);
    sfree(allocated);
    freetree234(tree);
    return;

  fail:
    sfree(results);
    for (i = 0; i < alloc_count; i++)
        sfree(allocated[i]);
    sfree(allocated);
    freetree234(tree);
}

/* ------------------------------------------------------------------ */
/* matching.c harness.                                                */
/* ------------------------------------------------------------------ */

static void do_matching(const jobject *req)
{
    const char *adjtext = field_string(req, "adj");
    const char *seed = field_string(req, "seed");
    long nl = 0, nr = 0;
    int **adj;
    int *sizes;
    int *outl, *outr;
    const char *p;
    random_state *rs = NULL;
    int count, i, emitted;

    if (!field_int(req, "nl", &nl) || !field_int(req, "nr", &nr) ||
        nl < 0 || nr < 0 || nl > 5000 || nr > 5000) {
        put_error("matching needs 0 <= nl, nr <= 5000");
        return;
    }

    adj = snewn((int)(nl ? nl : 1), int *);
    sizes = snewn((int)(nl ? nl : 1), int);
    for (i = 0; i < nl; i++) {
        adj[i] = NULL;
        sizes[i] = 0;
    }

    p = adjtext ? adjtext : "";
    for (i = 0; i < nl; i++) {
        int capacity = 0;
        while (*p && *p != ';') {
            char *end;
            long value;
            while (*p == ' ' || *p == '\t' || *p == ',')
                p++;
            if (!*p || *p == ';')
                break;
            value = strtol(p, &end, 10);
            if (end == p || value < 0 || value >= nr) {
                put_error("matching adjacency is invalid");
                return;
            }
            if (sizes[i] == capacity) {
                capacity = capacity ? capacity * 2 : 8;
                adj[i] = sresize(adj[i], capacity, int);
            }
            adj[i][sizes[i]++] = (int)value;
            p = end;
        }
        if (*p == ';')
            p++;
    }
    if (*p) {
        put_error("matching has too many adjacency lists");
        return;
    }

    if (seed)
        rs = random_new(seed, (int)strlen(seed));
    outl = snewn((int)(nl ? nl : 1), int);
    outr = snewn((int)(nr ? nr : 1), int);
    count = matching((int)nl, (int)nr, adj, sizes, rs, outl, outr);

    printf("{\"ok\":true,\"count\":%d,\"outL\":[", count);
    for (i = 0; i < nl; i++)
        printf("%s%d", i ? "," : "", outl[i]);
    fputs("],\"outR\":[", stdout);
    for (i = 0; i < nr; i++)
        printf("%s%d", i ? "," : "", outr[i]);
    fputs("],\"adj\":\"", stdout);
    for (i = 0; i < nl; i++) {
        int j;
        if (i)
            putchar(';');
        for (j = 0; j < sizes[i]; j++)
            printf("%s%d", j ? " " : "", adj[i][j]);
    }
    fputs("\"}\n", stdout);

    if (rs)
        random_free(rs);
    for (i = 0; i < nl; i++)
        sfree(adj[i]);
    sfree(adj);
    sfree(sizes);
    sfree(outl);
    sfree(outr);
}

/* ------------------------------------------------------------------ */
/* findloop.c harness.                                                */
/* ------------------------------------------------------------------ */

struct findloop_ctx {
    int n;
    int **adj;
    int *deg;
    int current;
    int position;
};

static int findloop_neighbour(int vertex, void *vctx)
{
    struct findloop_ctx *ctx = vctx;

    if (vertex >= 0) {
        ctx->current = vertex;
        ctx->position = 0;
    }
    if (ctx->current < 0)
        return -1;
    if (ctx->position < ctx->deg[ctx->current])
        return ctx->adj[ctx->current][ctx->position++];
    return -1;
}

static bool parse_edge(const char **p, long *u, long *v)
{
    char *end;

    while (**p == ' ' || **p == '\t' || **p == ',')
        (*p)++;
    if (!**p)
        return false;
    *u = strtol(*p, &end, 10);
    if (end == *p)
        return false;
    *p = end;
    while (**p == ' ' || **p == '\t')
        (*p)++;
    if (**p != '-')
        return false;
    (*p)++;
    *v = strtol(*p, &end, 10);
    if (end == *p)
        return false;
    *p = end;
    return true;
}

static void do_findloop(const jobject *req)
{
    const char *edges = field_string(req, "edges");
    long n = 0;
    struct findloop_ctx ctx = {0};
    struct findloopstate *state;
    const char *p;
    bool any_loop;
    int i, u, v, emitted;

    if (!field_int(req, "n", &n) || n < 1 || n > 2000) {
        put_error("findloop needs 1 <= n <= 2000");
        return;
    }
    ctx.n = (int)n;
    ctx.deg = snewn((int)n, int);
    for (i = 0; i < n; i++)
        ctx.deg[i] = 0;

    p = edges ? edges : "";
    while (*p) {
        long a, b;
        if (!parse_edge(&p, &a, &b) || a < 0 || b < 0 || a >= n || b >= n ||
            a == b) {
            sfree(ctx.deg);
            put_error("findloop edges must be distinct valid u-v pairs");
            return;
        }
        ctx.deg[a]++;
        ctx.deg[b]++;
    }

    ctx.adj = snewn((int)n, int *);
    for (i = 0; i < n; i++) {
        ctx.adj[i] = snewn(ctx.deg[i] ? ctx.deg[i] : 1, int);
        ctx.deg[i] = 0;
    }

    p = edges ? edges : "";
    while (*p) {
        long a, b;
        if (!parse_edge(&p, &a, &b))
            break;
        ctx.adj[a][ctx.deg[a]++] = (int)b;
        ctx.adj[b][ctx.deg[b]++] = (int)a;
    }

    state = findloop_new_state((int)n);
    any_loop = findloop_run(state, (int)n, findloop_neighbour, &ctx);

    printf("{\"ok\":true,\"anyLoop\":%s,\"loopEdges\":[",
           any_loop ? "true" : "false");
    emitted = 0;
    for (u = 0; u < n; u++) {
        for (v = u + 1; v < n; v++) {
            if (findloop_is_loop_edge(state, u, v)) {
                printf("%s\"%d,%d\"", emitted ? "," : "", u, v);
                emitted++;
            }
        }
    }
    fputs("],\"bridges\":[", stdout);
    emitted = 0;
    for (u = 0; u < n; u++) {
        for (v = u + 1; v < n; v++) {
            int uv = 0, vv = 0;
            if (findloop_is_bridge(state, u, v, &uv, &vv)) {
                printf("%s\"%d,%d,%d,%d\"", emitted ? "," : "", u, v, uv, vv);
                emitted++;
            }
        }
    }
    fputs("]}\n", stdout);

    findloop_free_state(state);
    for (i = 0; i < n; i++)
        sfree(ctx.adj[i]);
    sfree(ctx.adj);
    sfree(ctx.deg);
}

/* ------------------------------------------------------------------ */
/* Deterministic helpers: arraysort and combinations.                 */
/* ------------------------------------------------------------------ */

struct sort_mode {
    const char *name;
};

static int compare_longs(const void *av, const void *bv, void *ctx)
{
    const struct sort_mode *mode = ctx;
    long a = *(const long *)av, b = *(const long *)bv;

    if (!strcmp(mode->name, "lowbits")) {
        int ab = (int)(a & 7), bb = (int)(b & 7);
        if (ab != bb)
            return ab - bb;
    }
    if (!strcmp(mode->name, "desc"))
        return (a < b) ? 1 : (a > b) ? -1 : 0;
    return (a < b) ? -1 : (a > b) ? 1 : 0;
}

/*
 * Sorts a space-separated integer list with upstream arraysort(). The
 * Kotlin port must produce the same order, including for equal keys.
 */
static void do_sort(const jobject *req)
{
    const char *text = field_string(req, "values");
    const char *name = field_string(req, "mode");
    struct sort_mode mode = { name ? name : "asc" };
    long *values = NULL;
    int count = 0, capacity = 0, i;
    const char *p;

    if (!text) {
        put_error("sort needs values");
        return;
    }
    p = text;
    while (*p) {
        char *end;
        long v;
        while (*p == ' ' || *p == '\t' || *p == ',')
            p++;
        if (!*p)
            break;
        v = strtol(p, &end, 10);
        if (end == p) {
            sfree(values);
            put_error("sort values must be integers");
            return;
        }
        if (count == capacity) {
            capacity = capacity ? capacity * 2 : 16;
            values = sresize(values, capacity, long);
        }
        values[count++] = v;
        p = end;
    }

    arraysort_fn(values, count, sizeof(long), compare_longs, &mode);

    fputs("{\"ok\":true,\"values\":[", stdout);
    for (i = 0; i < count; i++)
        printf("%s%ld", i ? "," : "", values[i]);
    fputs("]}\n", stdout);
    sfree(values);
}

/*
 * Enumerates combinations in upstream order. The response is a list of
 * comma-separated index strings, which is enough to compare the exact
 * iteration order with the Kotlin port.
 */
static void do_combi(const jobject *req)
{
    long r = 0, n = 0;
    combi_ctx *combi;
    int total, emitted = 0, i;

    if (!field_int(req, "r", &r) || !field_int(req, "n", &n) ||
        r < 1 || n < 1 || r > n || n > 12) {
        put_error("combi needs 1 <= r <= n <= 12");
        return;
    }
    combi = new_combi((int)r, (int)n);
    if (combi->total > 100000) {
        free_combi(combi);
        put_error("combi result is too large");
        return;
    }
    total = combi->total;

    printf("{\"ok\":true,\"total\":%d,\"combinations\":[", total);
    while (next_combi(combi) != NULL) {
        printf("%s\"", emitted ? "," : "");
        for (i = 0; i < combi->r; i++)
            printf("%s%d", i ? "," : "", combi->a[i]);
        printf("\"");
        emitted++;
    }
    fputs("]}\n", stdout);
    free_combi(combi);
}

/*
 * Random streams. The parity tests compare the Kotlin port of random.c
 * with the C implementation, so this operation exposes the exact
 * values and the encoded state after the draw. A request carries either
 * a seed or an encoded state; `bits` selects random_bits, `limit`
 * selects random_upto. The response always carries the encoded state,
 * so a test can continue a stream on either side.
 */
static void do_random(const jobject *req, bool upto)
{
    const char *seed = field_string(req, "seed");
    const char *state = field_string(req, "state");
    long bits = 8, limit = 0, count = 1;
    random_state *rs;
    char *encoded;
    int i;

    if (state)
        rs = random_state_decode(state);
    else if (seed)
        rs = random_new(seed, (int)strlen(seed));
    else {
        put_error("random needs a seed or a state");
        return;
    }

    if (upto) {
        if (!field_int(req, "limit", &limit) || limit <= 0) {
            random_free(rs);
            put_error("randomUpto needs a positive limit");
            return;
        }
    } else {
        if (!field_int(req, "bits", &bits) || bits < 1 || bits > 31) {
            random_free(rs);
            put_error("randomBits needs bits in 1..31");
            return;
        }
    }
    if (!field_int(req, "count", &count))
        count = 1;
    if (count < 0 || count > 1000000) {
        random_free(rs);
        put_error("random count is out of range");
        return;
    }

    fputs("{\"ok\":true,\"values\":[", stdout);
    for (i = 0; i < count; i++) {
        unsigned long value = upto ? random_upto(rs, (unsigned long)limit)
                                   : random_bits(rs, (int)bits);
        printf("%s%lu", i ? "," : "", value);
    }
    encoded = random_state_encode(rs);
    fputs("],\"state\":", stdout);
    put_json_string(encoded);
    fputs("}\n", stdout);

    sfree(encoded);
    random_free(rs);
}

static const char *open_state_sized(const char *save, frontend *fe,
                                    long view_width, long view_height,
                                    int *out_w, int *out_h)
{
    const char *error = open_state(fe, save);
    int w, h;

    if (error)
        return error;
    w = (int)view_width;
    h = (int)view_height;
    midend_size(fe->me, &w, &h, true, 1.0);
    *out_w = w;
    *out_h = h;
    return NULL;
}

static void do_layout(const jobject *req)
{
    const char *save = field_string(req, "state");
    long view_width = 0, view_height = 0;
    frontend fe = {0};
    const char *error;
    int w, h;

    if (!save || !field_int(req, "w", &view_width) ||
        !field_int(req, "h", &view_height) ||
        view_width < 1 || view_height < 1 ||
        view_width > 100000 || view_height > 100000) {
        put_error("layout needs a state and positive w and h");
        return;
    }

    error = open_state(&fe, save);
    if (error) {
        put_error(error);
        return;
    }
    w = (int)view_width;
    h = (int)view_height;
    midend_size(fe.me, &w, &h, true, 1.0);
    printf("{\"ok\":true,\"w\":%d,\"h\":%d}\n", w, h);
    free_frontend(&fe);
}

static void do_key_sized(const jobject *req)
{
    const char *save = field_string(req, "state");
    long view_width = 0, view_height = 0;
    long x = 0, y = 0, k = 0;
    frontend fe = {0};
    const char *error;
    int w, h, ox, oy;

    if (!save || !field_int(req, "w", &view_width) ||
        !field_int(req, "h", &view_height) ||
        !field_int(req, "x", &x) || !field_int(req, "y", &y) ||
        !field_int(req, "k", &k) ||
        view_width < 1 || view_height < 1 ||
        view_width > 100000 || view_height > 100000) {
        put_error("keySized needs a state, w, h, x, y, and k");
        return;
    }

    error = open_state_sized(save, &fe, view_width, view_height, &w, &h);
    if (error) {
        put_error(error);
        return;
    }
    /* The shell subtracts the letterbox origin before the midend, as
     * android.c does in GameEngineImpl_keyEvent. */
    ox = ((int)view_width - w) / 2;
    oy = ((int)view_height - h) / 2;
    midend_process_key(fe.me, (int)x - ox, (int)y - oy, (int)k);
    put_state(&fe);
    free_frontend(&fe);
}

static void do_gesture_sized(const jobject *req)
{
    const char *save = field_string(req, "state");
    long view_width = 0, view_height = 0;
    long x0 = 0, y0 = 0, x1 = 0, y1 = 0, steps = 0;
    frontend fe = {0};
    const char *error;
    long i;
    int w, h, ox, oy;

    if (!save || !field_int(req, "w", &view_width) ||
        !field_int(req, "h", &view_height) ||
        !field_int(req, "x0", &x0) || !field_int(req, "y0", &y0) ||
        !field_int(req, "x1", &x1) || !field_int(req, "y1", &y1) ||
        !field_int(req, "steps", &steps) ||
        view_width < 1 || view_height < 1 ||
        view_width > 100000 || view_height > 100000 ||
        steps < 1 || steps > 1000) {
        put_error("gestureSized needs a state, w, h, x0, y0, x1, y1, and steps");
        return;
    }

    error = open_state_sized(save, &fe, view_width, view_height, &w, &h);
    if (error) {
        put_error(error);
        return;
    }
    ox = ((int)view_width - w) / 2;
    oy = ((int)view_height - h) / 2;

    midend_process_key(fe.me, (int)x0 - ox, (int)y0 - oy, LEFT_BUTTON);
    for (i = 1; i <= steps; i++) {
        long x = x0 + (x1 - x0) * i / steps;
        long y = y0 + (y1 - y0) * i / steps;
        midend_process_key(fe.me, (int)x - ox, (int)y - oy, LEFT_DRAG);
    }
    midend_process_key(fe.me, (int)x1 - ox, (int)y1 - oy, LEFT_RELEASE);
    put_state(&fe);
    free_frontend(&fe);
}

/*
 * Replays a list of pointer clicks inside one midend. One click is
 * `x,y,button`; clicks are separated by `;`. The coordinates are view
 * coordinates, so the letterbox origin is subtracted here as android.c
 * does in GameEngineImpl_keyEvent. A cursor game keeps its game_ui
 * between clicks, which is what makes a select-then-remove pair work.
 * Returns NULL on success.
 */
static const char *process_points(frontend *fe, const char *points,
                                  int ox, int oy)
{
    const char *p = points;

    while (*p) {
        long x, y, button;
        char *end;

        while (*p == ' ' || *p == '\t' || *p == ';')
            p++;
        if (!*p)
            break;
        x = strtol(p, &end, 10);
        if (end == p)
            return "points needs x,y,button triples";
        p = end;
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p++ != ',')
            return "points needs x,y,button triples";
        y = strtol(p, &end, 10);
        if (end == p)
            return "points needs x,y,button triples";
        p = end;
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p++ != ',')
            return "points needs x,y,button triples";
        button = strtol(p, &end, 10);
        if (end == p)
            return "points needs x,y,button triples";
        p = end;
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p && *p != ';')
            return "points needs x,y,button triples";
        midend_process_key(fe->me, (int)x - ox, (int)y - oy, (int)button);
    }
    return NULL;
}

/*
 * Replays sized pointer clicks and, when asked, records one final
 * redraw. The redraw is the same shape as a plain trace: a fresh
 * drawstate, the full board, exactly one status line, the full-window
 * update, and end_draw.
 *
 * drawing.c caches the last status text and suppresses an unchanged
 * line. The sequence has already drawn that text, so the final redraw
 * would lose it. Reset the cache with a string that differs from the
 * last one, then discard that operation: the final redraw then emits
 * the status exactly once.
 */
static void do_points_sized(const jobject *req)
{
    const char *save = field_string(req, "state");
    const char *points = field_string(req, "points");
    long view_width = 0, view_height = 0, want_trace = 0;
    bool sized;
    frontend fe = {0};
    trace_ctx trace = {0};
    const char *error;
    int w, h, ox = 0, oy = 0;

    if (!save || !points) {
        put_error("pointsSized needs a state and points");
        return;
    }
    sized = field_int(req, "w", &view_width) &&
        field_int(req, "h", &view_height);
    if (sized && (view_width < 1 || view_height < 1 ||
                  view_width > 100000 || view_height > 100000)) {
        put_error("pointsSized needs positive w and h");
        return;
    }
    (void)field_int(req, "trace", &want_trace);

    error = open_state_with(&fe, save, &recording_drawing, &trace);
    if (error) {
        put_error(error);
        return;
    }
    if (sized) {
        w = (int)view_width;
        h = (int)view_height;
        midend_size(fe.me, &w, &h, true, 1.0);
        /*
         * The state fields and the trace header report the frontend
         * size. It must be the size the midend draws at, not the
         * preferred size that open_state reported before sizing.
         */
        fe.width = w;
        fe.height = h;
        ox = ((int)view_width - w) / 2;
        oy = ((int)view_height - h) / 2;
    }

    error = process_points(&fe, points, ox, oy);
    if (error)
        goto fail;

    if (want_trace) {
        if (trace.dr && trace.last_status) {
            char *poison = snewn(strlen(trace.last_status) + 2, char);
            sprintf(poison, "%s ", trace.last_status);
            status_bar(trace.dr, poison);
            sfree(poison);
        }
        trace.ops.len = 0;
        if (trace.ops.buf)
            trace.ops.buf[0] = '\0';
        trace.nops = 0;
        trace.blitters = 0;
        trace.suppress_blitter_free = true;
        midend_force_redraw(fe.me);
    }

    fputs("{\"ok\":true,", stdout);
    put_state_fields(&fe);
    if (want_trace) {
        fputs(",\"trace\":", stdout);
        put_trace_body(&fe, &trace);
    }
    fputs("}\n", stdout);

    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
    return;

  fail:
    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
    put_error(error);
}

/*
 * Records one redraw on a drawstate that the app would already have.
 *
 * Every other trace operation creates a fresh drawstate, so a caching
 * game never records the warm path: the dirty-cell test, the `started`
 * flag, or the `blitter_load` that restores the background under a
 * dragged sprite. This operation opens the save, suppresses recording
 * while it draws the first frame and applies a warm pointer sequence
 * (`x,y,button` triples, with an optional `w`/`h` viewport and its
 * letterbox origin), then keeps the drawstate and the blitter ids,
 * clears the recorded operations, enables recording, and calls
 * `midend_redraw` once.
 *
 * Suppression happens in the trace callbacks. Blitter allocation and
 * free still change the id counter, and every drawing call still
 * updates the game's drawstate, so the final redraw sees exactly the
 * cache that the warm frames produced.
 */
static void do_trace_warm(const jobject *req)
{
    const char *save = field_string(req, "state");
    const char *points = field_string(req, "points");
    long view_width = 0, view_height = 0;
    bool sized;
    frontend fe = {0};
    trace_ctx trace = {0};
    const char *error;
    int w, h, ox = 0, oy = 0;

    if (!save || !points) {
        put_error("traceWarm needs a state and points");
        return;
    }
    sized = field_int(req, "w", &view_width) &&
        field_int(req, "h", &view_height);
    if (sized && (view_width < 1 || view_height < 1 ||
                  view_width > 100000 || view_height > 100000)) {
        put_error("traceWarm needs positive w and h");
        return;
    }

    /*
     * Do not size while opening: the drawstate must be created once,
     * at the requested tile size, so the warm trace refers to blitter
     * id 1 like the Kotlin sink. `midend_deserialise` creates the
     * drawstate without allocating its blitter.
     */
    error = open_state_with_unsized(&fe, save, &recording_drawing, &trace, false);
    if (error) {
        put_error(error);
        return;
    }

    if (sized) {
        w = (int)view_width;
        h = (int)view_height;
        midend_size(fe.me, &w, &h, true, 1.0);
        fe.width = w;
        fe.height = h;
        ox = ((int)view_width - w) / 2;
        oy = ((int)view_height - h) / 2;
    } else {
        size_frontend(&fe);
    }

    trace.discard = true;
    /* The first draw fills the cache and clears `first_draw`. */
    midend_redraw(fe.me);

    error = process_points(&fe, points, ox, oy);
    if (error)
        goto fail;

    /* Drop the warm operations. Keep the drawstate and its blitter ids. */
    trace.ops.len = 0;
    if (trace.ops.buf)
        trace.ops.buf[0] = '\0';
    trace.nops = 0;
    trace.discard = false;

    midend_redraw(fe.me);

    fputs("{\"ok\":true,", stdout);
    put_state_fields(&fe);
    fputs(",\"trace\":", stdout);
    put_trace_body(&fe, &trace);
    fputs("}\n", stdout);

    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
    return;

  fail:
    free_frontend(&fe);
    sfree(trace.ops.buf);
    sfree(trace.last_status);
    put_error(error);
}

static void do_state_op(const char *op, const jobject *req)
{
    const char *save = field_string(req, "state");
    frontend fe = {0};
    const char *error;

    if (!save) {
        put_error("missing state");
        return;
    }

    error = open_state(&fe, save);
    if (error) {
        put_error(error);
        return;
    }

    if (!strcmp(op, "id") || !strcmp(op, "status")) {
        put_state(&fe);
    } else if (!strcmp(op, "key")) {
        long x = 0, y = 0, k = 0;

        if (!field_int(req, "x", &x) || !field_int(req, "y", &y) ||
            !field_int(req, "k", &k)) {
            free_frontend(&fe);
            put_error("key needs x, y, k");
            return;
        }
        midend_process_key(fe.me, (int)x, (int)y, (int)k);
        put_state(&fe);
    } else if (!strcmp(op, "undo")) {
        midend_process_key(fe.me, 0, 0, UI_UNDO);
        put_state(&fe);
    } else if (!strcmp(op, "redo")) {
        midend_process_key(fe.me, 0, 0, UI_REDO);
        put_state(&fe);
    } else if (!strcmp(op, "solve")) {
        error = midend_solve(fe.me);
        if (error) {
            free_frontend(&fe);
            put_error(error);
            return;
        }
        put_state(&fe);
    } else if (!strcmp(op, "text")) {
        char *text = midend_text_format(fe.me);
        fputs("{\"ok\":true,\"text\":", stdout);
        put_json_string(text);
        fputs("}\n", stdout);
        sfree(text);
    } else if (!strcmp(op, "requestkeys")) {
        int nkeys = 0, arrow_mode = 0, i;
        long full_cursor = 0;
        key_label *keys;

        /*
         * The game reads the Android full-cursor hint from the
         * pre-set arrow mode, and `midend_request_keys` always passes
         * false. The by-game entry point is the only way to ask for
         * the full-grid request, which palisade returns as four
         * arrow-only labels.
         */
        if (field_int(req, "fullCursor", &full_cursor) && full_cursor != 0) {
            const game *g = midend_which_game(fe.me);
            game_params *params = midend_get_params(fe.me);
            keys = midend_request_keys_by_game(&nkeys, g, params,
                                               &arrow_mode, true);
            g->free_params(params);
        } else {
            keys = midend_request_keys(fe.me, &nkeys, &arrow_mode);
        }

        printf("{\"ok\":true,\"arrowMode\":%d,\"labels\":[", arrow_mode);
        for (i = 0; i < nkeys; i++) {
            printf("%s{\"label\":", i ? "," : "");
            put_json_string(keys[i].label ? keys[i].label : "");
            printf(",\"button\":%d,\"needsArrows\":%s}",
                   keys[i].button, keys[i].needs_arrows ? "true" : "false");
        }
        fputs("]}\n", stdout);
        free_keys(keys, nkeys);
    } else {
        free_frontend(&fe);
        put_error("unknown state operation");
        return;
    }

    free_frontend(&fe);
}

/* ------------------------------------------------------------------ */
/* Main loop.                                                         */
/* ------------------------------------------------------------------ */

static char *read_line(FILE *fp)
{
    size_t cap = 4096;
    size_t len = 0;
    char *buf = snewn(cap, char);
    int c;

    while ((c = fgetc(fp)) != EOF && c != '\n') {
        if (len + 2 > cap) {
            cap *= 2;
            buf = sresize(buf, cap, char);
        }
        buf[len++] = (char)c;
    }
    if (c == EOF && len == 0) {
        sfree(buf);
        return NULL;
    }
    buf[len] = '\0';
    return buf;
}

int main(int argc, char **argv)
{
    char *line;

    (void)argc;
    (void)argv;

    while ((line = read_line(stdin)) != NULL) {
        jobject req;
        const char *op;

        if (!parse_object(line, &req)) {
            put_error("invalid JSON request");
            sfree(line);
            continue;
        }
        sfree(line);

        op = field_string(&req, "op");
        if (!op) {
            put_error("missing op");
        } else if (!strcmp(op, "games")) {
            do_games();
        } else if (!strcmp(op, "presets")) {
            const char *game = field_string(&req, "game");
            if (!game)
                put_error("presets needs a game");
            else
                do_presets(game);
        } else if (!strcmp(op, "new")) {
            const char *game = field_string(&req, "game");
            if (!game)
                put_error("new needs a game");
            else
                do_new(game, field_string(&req, "params"),
                       field_string(&req, "seed"));
        } else if (!strcmp(op, "id") || !strcmp(op, "status") ||
                   !strcmp(op, "key") || !strcmp(op, "undo") ||
                   !strcmp(op, "redo") || !strcmp(op, "solve") ||
                   !strcmp(op, "text") || !strcmp(op, "requestkeys")) {
            do_state_op(op, &req);
        } else if (!strcmp(op, "layout")) {
            do_layout(&req);
        } else if (!strcmp(op, "keySized")) {
            do_key_sized(&req);
        } else if (!strcmp(op, "gestureSized")) {
            do_gesture_sized(&req);
        } else if (!strcmp(op, "pointsSized")) {
            do_points_sized(&req);
        } else if (!strcmp(op, "traceWarm")) {
            do_trace_warm(&req);
        } else if (!strcmp(op, "keys")) {
            do_key_seq(field_string(&req, "state"),
                       field_string(&req, "seq"),
                       field_string(&req, "prefs"));
        } else if (!strcmp(op, "trace")) {
            do_trace(&req);
        } else if (!strcmp(op, "tree")) {
            do_tree(&req);
        } else if (!strcmp(op, "matching")) {
            do_matching(&req);
        } else if (!strcmp(op, "findloop")) {
            do_findloop(&req);
        } else if (!strcmp(op, "dsf")) {
            do_dsf(&req);
        } else if (!strcmp(op, "divvy")) {
            do_divvy(&req);
        } else if (!strcmp(op, "laydomino")) {
            do_laydomino(&req);
        } else if (!strcmp(op, "grid")) {
            do_grid(&req);
        } else if (!strcmp(op, "latin")) {
            do_latin(&req);
        } else if (!strcmp(op, "tdq")) {
            do_tdq(&req);
        } else if (!strcmp(op, "sort")) {
            do_sort(&req);
        } else if (!strcmp(op, "combi")) {
            do_combi(&req);
        } else if (!strcmp(op, "randomBits") ||
                   !strcmp(op, "randomUpto")) {
            do_random(&req, !strcmp(op, "randomUpto"));
        } else if (!strcmp(op, "traceNew")) {
            const char *game = field_string(&req, "game");
            if (!game)
                put_error("traceNew needs a game");
            else
                do_trace_new(game, field_string(&req, "params"),
                             field_string(&req, "seed"));
        } else {
            put_error("unknown op");
        }

        free_object(&req);
        fflush(stdout);
    }

    return 0;
}

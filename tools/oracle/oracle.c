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
 *   {"op":"undo","state":"..."}
 *   {"op":"redo","state":"..."}
 *   {"op":"solve","state":"..."}
 *   {"op":"text","state":"..."}
 *   {"op":"status","state":"..."}
 *
 * Responses (flat JSON objects):
 *
 *   {"ok":true,...}
 *   {"ok":false,"error":"..."}
 *
 * State responses carry: state, id, game, w, h, canundo, canredo,
 * and status. The status value follows midend_status(): 1 win, -1 lose,
 * 0 open.
 */

#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "puzzles.h"

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

static void put_state(frontend *fe)
{
    sbuf save = {0};
    char *id;

    midend_serialise(fe->me, sbuf_write_cb, &save);
    if (!save.buf)
        sbuf_add(&save, "", 0);
    id = midend_get_game_id(fe->me);

    fputs("{\"ok\":true,\"state\":", stdout);
    put_json_string(save.buf);
    fputs(",\"id\":", stdout);
    put_json_string(id);
    fputs(",\"game\":", stdout);
    put_json_string(midend_which_game(fe->me)->name);
    printf(",\"w\":%d,\"h\":%d,\"canundo\":%s,\"canredo\":%s,\"status\":%d}\n",
           fe->width, fe->height,
           midend_can_undo(fe->me) ? "true" : "false",
           midend_can_redo(fe->me) ? "true" : "false",
           midend_status(fe->me));

    sfree(id);
    sfree(save.buf);
}

/* Opens a midend from a serialised save file. Returns NULL on success
 * and an error message on failure. */
static const char *open_state(frontend *fe, const char *save)
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

    fe->me = midend_new(fe, g, &oracle_drawing, NULL);
    rd.pos = 0;
    error = midend_deserialise(fe->me, rbuf_read_cb, &rd);
    if (error) {
        free_frontend(fe);
        return error;
    }
    size_frontend(fe);
    return NULL;
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

static void do_new(const char *gamename, const char *params,
                   const char *seed)
{
    const game *g = find_game(gamename);
    frontend fe = {0};
    char *id;
    const char *error;

    if (!g) {
        put_error("unknown game");
        return;
    }
    if (!seed) {
        put_error("new needs a seed");
        return;
    }

    fe.me = midend_new(&fe, g, &oracle_drawing, NULL);

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
    error = midend_game_id_int(fe.me, id, DEF_PARAMS, false);
    sfree(id);
    if (error) {
        free_frontend(&fe);
        put_error(error);
        return;
    }
    midend_new_game(fe.me);
    size_frontend(&fe);
    put_state(&fe);
    free_frontend(&fe);
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
                   !strcmp(op, "text")) {
            do_state_op(op, &req);
        } else {
            put_error("unknown op");
        }

        free_object(&req);
        fflush(stdout);
    }

    return 0;
}

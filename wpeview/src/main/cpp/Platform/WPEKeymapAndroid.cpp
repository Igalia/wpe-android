/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "WPEKeymapAndroid.h"

#include "Logging.h"

struct _WPEKeymapAndroid {
    WPEKeymap parent;
};

G_DEFINE_FINAL_TYPE(WPEKeymapAndroid, wpe_keymap_android, WPE_TYPE_KEYMAP)

namespace {

struct KeymapKey {
    uint32_t keycode;
    uint32_t level0;
    uint32_t level1;
};

constexpr KeymapKey s_keys[] = {
    {KEY_A + 8, XKB_KEY_a, XKB_KEY_A},
    {KEY_B + 8, XKB_KEY_b, XKB_KEY_B},
    {KEY_C + 8, XKB_KEY_c, XKB_KEY_C},
    {KEY_D + 8, XKB_KEY_d, XKB_KEY_D},
    {KEY_E + 8, XKB_KEY_e, XKB_KEY_E},
    {KEY_F + 8, XKB_KEY_f, XKB_KEY_F},
    {KEY_G + 8, XKB_KEY_g, XKB_KEY_G},
    {KEY_H + 8, XKB_KEY_h, XKB_KEY_H},
    {KEY_I + 8, XKB_KEY_i, XKB_KEY_I},
    {KEY_J + 8, XKB_KEY_j, XKB_KEY_J},
    {KEY_K + 8, XKB_KEY_k, XKB_KEY_K},
    {KEY_L + 8, XKB_KEY_l, XKB_KEY_L},
    {KEY_M + 8, XKB_KEY_m, XKB_KEY_M},
    {KEY_N + 8, XKB_KEY_n, XKB_KEY_N},
    {KEY_O + 8, XKB_KEY_o, XKB_KEY_O},
    {KEY_P + 8, XKB_KEY_p, XKB_KEY_P},
    {KEY_Q + 8, XKB_KEY_q, XKB_KEY_Q},
    {KEY_R + 8, XKB_KEY_r, XKB_KEY_R},
    {KEY_S + 8, XKB_KEY_s, XKB_KEY_S},
    {KEY_T + 8, XKB_KEY_t, XKB_KEY_T},
    {KEY_U + 8, XKB_KEY_u, XKB_KEY_U},
    {KEY_V + 8, XKB_KEY_v, XKB_KEY_V},
    {KEY_W + 8, XKB_KEY_w, XKB_KEY_W},
    {KEY_X + 8, XKB_KEY_x, XKB_KEY_X},
    {KEY_Y + 8, XKB_KEY_y, XKB_KEY_Y},
    {KEY_Z + 8, XKB_KEY_z, XKB_KEY_Z},

    {KEY_1 + 8, XKB_KEY_1, XKB_KEY_exclam},
    {KEY_2 + 8, XKB_KEY_2, XKB_KEY_at},
    {KEY_3 + 8, XKB_KEY_3, XKB_KEY_numbersign},
    {KEY_4 + 8, XKB_KEY_4, XKB_KEY_dollar},
    {KEY_5 + 8, XKB_KEY_5, XKB_KEY_percent},
    {KEY_6 + 8, XKB_KEY_6, XKB_KEY_asciicircum},
    {KEY_7 + 8, XKB_KEY_7, XKB_KEY_ampersand},
    {KEY_8 + 8, XKB_KEY_8, XKB_KEY_asterisk},
    {KEY_9 + 8, XKB_KEY_9, XKB_KEY_parenleft},
    {KEY_0 + 8, XKB_KEY_0, XKB_KEY_parenright},

    {KEY_MINUS + 8, XKB_KEY_minus, XKB_KEY_underscore},
    {KEY_EQUAL + 8, XKB_KEY_equal, XKB_KEY_plus},
    {KEY_LEFTBRACE + 8, XKB_KEY_bracketleft, XKB_KEY_braceleft},
    {KEY_RIGHTBRACE + 8, XKB_KEY_bracketright, XKB_KEY_braceright},
    {KEY_BACKSLASH + 8, XKB_KEY_backslash, XKB_KEY_bar},
    {KEY_SEMICOLON + 8, XKB_KEY_semicolon, XKB_KEY_colon},
    {KEY_APOSTROPHE + 8, XKB_KEY_apostrophe, XKB_KEY_quotedbl},
    {KEY_GRAVE + 8, XKB_KEY_grave, XKB_KEY_asciitilde},
    {KEY_COMMA + 8, XKB_KEY_comma, XKB_KEY_less},
    {KEY_DOT + 8, XKB_KEY_period, XKB_KEY_greater},
    {KEY_SLASH + 8, XKB_KEY_slash, XKB_KEY_question},

    {KEY_SPACE + 8, XKB_KEY_space, XKB_KEY_space},
    {KEY_ENTER + 8, XKB_KEY_Return, XKB_KEY_Return},
    {KEY_BACKSPACE + 8, XKB_KEY_BackSpace, XKB_KEY_BackSpace},
    {KEY_DELETE + 8, XKB_KEY_Delete, XKB_KEY_Delete},
    {KEY_ESC + 8, XKB_KEY_Escape, XKB_KEY_Escape},
    {KEY_TAB + 8, XKB_KEY_Tab, XKB_KEY_Tab},
    {KEY_UP + 8, XKB_KEY_Up, XKB_KEY_Up},
    {KEY_DOWN + 8, XKB_KEY_Down, XKB_KEY_Down},
    {KEY_LEFT + 8, XKB_KEY_Left, XKB_KEY_Left},
    {KEY_RIGHT + 8, XKB_KEY_Right, XKB_KEY_Right},
    {KEY_HOME + 8, XKB_KEY_Home, XKB_KEY_Home},
    {KEY_END + 8, XKB_KEY_End, XKB_KEY_End},
    {KEY_PAGEUP + 8, XKB_KEY_Page_Up, XKB_KEY_Page_Up},
    {KEY_PAGEDOWN + 8, XKB_KEY_Page_Down, XKB_KEY_Page_Down},
    {KEY_INSERT + 8, XKB_KEY_Insert, XKB_KEY_Insert},
    {KEY_LEFTSHIFT + 8, XKB_KEY_Shift_L, XKB_KEY_Shift_L},
    {KEY_RIGHTSHIFT + 8, XKB_KEY_Shift_R, XKB_KEY_Shift_R},
    {KEY_LEFTCTRL + 8, XKB_KEY_Control_L, XKB_KEY_Control_L},
    {KEY_RIGHTCTRL + 8, XKB_KEY_Control_R, XKB_KEY_Control_R},
    {KEY_LEFTALT + 8, XKB_KEY_Alt_L, XKB_KEY_Alt_L},
    {KEY_RIGHTALT + 8, XKB_KEY_Alt_R, XKB_KEY_Alt_R},
    {KEY_LEFTMETA + 8, XKB_KEY_Meta_L, XKB_KEY_Meta_L},
    {KEY_RIGHTMETA + 8, XKB_KEY_Meta_R, XKB_KEY_Meta_R},
    {KEY_CAPSLOCK + 8, XKB_KEY_Caps_Lock, XKB_KEY_Caps_Lock},
    {KEY_F1 + 8, XKB_KEY_F1, XKB_KEY_F1},
    {KEY_F2 + 8, XKB_KEY_F2, XKB_KEY_F2},
    {KEY_F3 + 8, XKB_KEY_F3, XKB_KEY_F3},
    {KEY_F4 + 8, XKB_KEY_F4, XKB_KEY_F4},
    {KEY_F5 + 8, XKB_KEY_F5, XKB_KEY_F5},
    {KEY_F6 + 8, XKB_KEY_F6, XKB_KEY_F6},
    {KEY_F7 + 8, XKB_KEY_F7, XKB_KEY_F7},
    {KEY_F8 + 8, XKB_KEY_F8, XKB_KEY_F8},
    {KEY_F9 + 8, XKB_KEY_F9, XKB_KEY_F9},
    {KEY_F10 + 8, XKB_KEY_F10, XKB_KEY_F10},
    {KEY_F11 + 8, XKB_KEY_F11, XKB_KEY_F11},
    {KEY_F12 + 8, XKB_KEY_F12, XKB_KEY_F12},

    {KEY_KP0 + 8, XKB_KEY_KP_0, XKB_KEY_KP_Insert},
    {KEY_KP1 + 8, XKB_KEY_KP_1, XKB_KEY_KP_End},
    {KEY_KP2 + 8, XKB_KEY_KP_2, XKB_KEY_KP_Down},
    {KEY_KP3 + 8, XKB_KEY_KP_3, XKB_KEY_KP_Page_Down},
    {KEY_KP4 + 8, XKB_KEY_KP_4, XKB_KEY_KP_Left},
    {KEY_KP5 + 8, XKB_KEY_KP_5, XKB_KEY_KP_Begin},
    {KEY_KP6 + 8, XKB_KEY_KP_6, XKB_KEY_KP_Right},
    {KEY_KP7 + 8, XKB_KEY_KP_7, XKB_KEY_KP_Home},
    {KEY_KP8 + 8, XKB_KEY_KP_8, XKB_KEY_KP_Up},
    {KEY_KP9 + 8, XKB_KEY_KP_9, XKB_KEY_KP_Page_Up},
    {KEY_KPDOT + 8, XKB_KEY_KP_Decimal, XKB_KEY_KP_Delete},
    {KEY_KPPLUS + 8, XKB_KEY_KP_Add, XKB_KEY_KP_Add},
    {KEY_KPMINUS + 8, XKB_KEY_KP_Subtract, XKB_KEY_KP_Subtract},
    {KEY_KPASTERISK + 8, XKB_KEY_KP_Multiply, XKB_KEY_KP_Multiply},
    {KEY_KPSLASH + 8, XKB_KEY_KP_Divide, XKB_KEY_KP_Divide},
    {KEY_KPENTER + 8, XKB_KEY_KP_Enter, XKB_KEY_KP_Enter},

    {KEY_PAUSE + 8, XKB_KEY_Pause, XKB_KEY_Pause},
    {KEY_HELP + 8, XKB_KEY_Help, XKB_KEY_Help},
    {KEY_CANCEL + 8, XKB_KEY_Cancel, XKB_KEY_Cancel},
    {KEY_NUMLOCK + 8, XKB_KEY_Num_Lock, XKB_KEY_Num_Lock},
    {KEY_SCROLLLOCK + 8, XKB_KEY_Scroll_Lock, XKB_KEY_Scroll_Lock},
};

const KeymapKey* findKey(uint32_t keycode)
{
    for (const auto& key : s_keys) {
        if (key.keycode == keycode)
            return &key;
    }
    return nullptr;
}

bool isLetter(const KeymapKey& key)
{
    return key.level0 >= XKB_KEY_a && key.level0 <= XKB_KEY_z;
}

} // namespace

static gboolean wpeKeymapAndroidGetEntriesForKeyval(
    WPEKeymap* keymap, guint keyval, WPEKeymapEntry** entries, guint* nEntries)
{
    UNUSED_PARAM(keymap);

    GArray* matches = g_array_new(FALSE, FALSE, sizeof(WPEKeymapEntry));
    for (const auto& key : s_keys) {
        if (key.level0 == keyval) {
            WPEKeymapEntry entry {key.keycode, 0, 0};
            g_array_append_val(matches, entry);
        }
        if (key.level1 == keyval && key.level1 != key.level0) {
            WPEKeymapEntry entry {key.keycode, 0, 1};
            g_array_append_val(matches, entry);
        }
    }

    if (!matches->len) {
        g_array_free(matches, TRUE);
        if (entries)
            *entries = nullptr;
        if (nEntries)
            *nEntries = 0;
        return FALSE;
    }

    if (nEntries)
        *nEntries = matches->len;
    if (entries)
        *entries = reinterpret_cast<WPEKeymapEntry*>(g_array_free(matches, FALSE));
    else
        g_array_free(matches, TRUE);
    return TRUE;
}

static gboolean wpeKeymapAndroidTranslateKeyboardState(WPEKeymap* keymap, guint hardware_keycode,
    WPEModifiers modifiers, int group, guint* keyval, int* effectiveGroup, int* level, WPEModifiers* consumed)
{
    UNUSED_PARAM(keymap);
    UNUSED_PARAM(group);

    const auto* key = findKey(hardware_keycode);
    if (!key) {
        if (keyval)
            *keyval = 0;
        if (effectiveGroup)
            *effectiveGroup = 0;
        if (level)
            *level = 0;
        if (consumed)
            *consumed = {};
        return FALSE;
    }

    bool shift = modifiers & WPE_MODIFIER_KEYBOARD_SHIFT;
    if (isLetter(*key) && (modifiers & WPE_MODIFIER_KEYBOARD_CAPS_LOCK))
        shift = !shift;
    bool usesLevel1 = shift && key->level1 != key->level0;

    if (keyval)
        *keyval = usesLevel1 ? key->level1 : key->level0;
    if (effectiveGroup)
        *effectiveGroup = 0;
    if (level)
        *level = usesLevel1 ? 1 : 0;
    if (consumed)
        *consumed = usesLevel1 ? WPE_MODIFIER_KEYBOARD_SHIFT : static_cast<WPEModifiers>(0);
    return TRUE;
}

static WPEModifiers wpeKeymapAndroidGetModifiers(WPEKeymap* keymap)
{
    UNUSED_PARAM(keymap);
    return {};
}

static void wpe_keymap_android_class_init(WPEKeymapAndroidClass* klass)
{
    WPEKeymapClass* keymapClass = WPE_KEYMAP_CLASS(klass);
    keymapClass->get_entries_for_keyval = wpeKeymapAndroidGetEntriesForKeyval;
    keymapClass->translate_keyboard_state = wpeKeymapAndroidTranslateKeyboardState;
    keymapClass->get_modifiers = wpeKeymapAndroidGetModifiers;
}

static void wpe_keymap_android_init(WPEKeymapAndroid* keymap)
{
    UNUSED_PARAM(keymap);
}

WPEKeymap* wpe_keymap_android_new(void)
{
    return WPE_KEYMAP(g_object_new(WPE_TYPE_KEYMAP_ANDROID, nullptr));
}

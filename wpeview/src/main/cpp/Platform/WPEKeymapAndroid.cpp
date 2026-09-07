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

// XKB keycodes are Linux evdev codes plus a fixed offset of 8, so that they stay
// clear of the range the X11 protocol reserves. See xkb_keycode_t in xkbcommon.h.
constexpr uint32_t xkbKeycode(uint32_t evdevCode)
{
    return evdevCode + 8;
}

constexpr KeymapKey s_keys[] = {
    {xkbKeycode(KEY_A), XKB_KEY_a, XKB_KEY_A},
    {xkbKeycode(KEY_B), XKB_KEY_b, XKB_KEY_B},
    {xkbKeycode(KEY_C), XKB_KEY_c, XKB_KEY_C},
    {xkbKeycode(KEY_D), XKB_KEY_d, XKB_KEY_D},
    {xkbKeycode(KEY_E), XKB_KEY_e, XKB_KEY_E},
    {xkbKeycode(KEY_F), XKB_KEY_f, XKB_KEY_F},
    {xkbKeycode(KEY_G), XKB_KEY_g, XKB_KEY_G},
    {xkbKeycode(KEY_H), XKB_KEY_h, XKB_KEY_H},
    {xkbKeycode(KEY_I), XKB_KEY_i, XKB_KEY_I},
    {xkbKeycode(KEY_J), XKB_KEY_j, XKB_KEY_J},
    {xkbKeycode(KEY_K), XKB_KEY_k, XKB_KEY_K},
    {xkbKeycode(KEY_L), XKB_KEY_l, XKB_KEY_L},
    {xkbKeycode(KEY_M), XKB_KEY_m, XKB_KEY_M},
    {xkbKeycode(KEY_N), XKB_KEY_n, XKB_KEY_N},
    {xkbKeycode(KEY_O), XKB_KEY_o, XKB_KEY_O},
    {xkbKeycode(KEY_P), XKB_KEY_p, XKB_KEY_P},
    {xkbKeycode(KEY_Q), XKB_KEY_q, XKB_KEY_Q},
    {xkbKeycode(KEY_R), XKB_KEY_r, XKB_KEY_R},
    {xkbKeycode(KEY_S), XKB_KEY_s, XKB_KEY_S},
    {xkbKeycode(KEY_T), XKB_KEY_t, XKB_KEY_T},
    {xkbKeycode(KEY_U), XKB_KEY_u, XKB_KEY_U},
    {xkbKeycode(KEY_V), XKB_KEY_v, XKB_KEY_V},
    {xkbKeycode(KEY_W), XKB_KEY_w, XKB_KEY_W},
    {xkbKeycode(KEY_X), XKB_KEY_x, XKB_KEY_X},
    {xkbKeycode(KEY_Y), XKB_KEY_y, XKB_KEY_Y},
    {xkbKeycode(KEY_Z), XKB_KEY_z, XKB_KEY_Z},

    {xkbKeycode(KEY_1), XKB_KEY_1, XKB_KEY_exclam},
    {xkbKeycode(KEY_2), XKB_KEY_2, XKB_KEY_at},
    {xkbKeycode(KEY_3), XKB_KEY_3, XKB_KEY_numbersign},
    {xkbKeycode(KEY_4), XKB_KEY_4, XKB_KEY_dollar},
    {xkbKeycode(KEY_5), XKB_KEY_5, XKB_KEY_percent},
    {xkbKeycode(KEY_6), XKB_KEY_6, XKB_KEY_asciicircum},
    {xkbKeycode(KEY_7), XKB_KEY_7, XKB_KEY_ampersand},
    {xkbKeycode(KEY_8), XKB_KEY_8, XKB_KEY_asterisk},
    {xkbKeycode(KEY_9), XKB_KEY_9, XKB_KEY_parenleft},
    {xkbKeycode(KEY_0), XKB_KEY_0, XKB_KEY_parenright},

    {xkbKeycode(KEY_MINUS), XKB_KEY_minus, XKB_KEY_underscore},
    {xkbKeycode(KEY_EQUAL), XKB_KEY_equal, XKB_KEY_plus},
    {xkbKeycode(KEY_LEFTBRACE), XKB_KEY_bracketleft, XKB_KEY_braceleft},
    {xkbKeycode(KEY_RIGHTBRACE), XKB_KEY_bracketright, XKB_KEY_braceright},
    {xkbKeycode(KEY_BACKSLASH), XKB_KEY_backslash, XKB_KEY_bar},
    {xkbKeycode(KEY_SEMICOLON), XKB_KEY_semicolon, XKB_KEY_colon},
    {xkbKeycode(KEY_APOSTROPHE), XKB_KEY_apostrophe, XKB_KEY_quotedbl},
    {xkbKeycode(KEY_GRAVE), XKB_KEY_grave, XKB_KEY_asciitilde},
    {xkbKeycode(KEY_COMMA), XKB_KEY_comma, XKB_KEY_less},
    {xkbKeycode(KEY_DOT), XKB_KEY_period, XKB_KEY_greater},
    {xkbKeycode(KEY_SLASH), XKB_KEY_slash, XKB_KEY_question},

    {xkbKeycode(KEY_SPACE), XKB_KEY_space, XKB_KEY_space},
    {xkbKeycode(KEY_ENTER), XKB_KEY_Return, XKB_KEY_Return},
    {xkbKeycode(KEY_BACKSPACE), XKB_KEY_BackSpace, XKB_KEY_BackSpace},
    {xkbKeycode(KEY_DELETE), XKB_KEY_Delete, XKB_KEY_Delete},
    {xkbKeycode(KEY_ESC), XKB_KEY_Escape, XKB_KEY_Escape},
    {xkbKeycode(KEY_TAB), XKB_KEY_Tab, XKB_KEY_Tab},
    {xkbKeycode(KEY_UP), XKB_KEY_Up, XKB_KEY_Up},
    {xkbKeycode(KEY_DOWN), XKB_KEY_Down, XKB_KEY_Down},
    {xkbKeycode(KEY_LEFT), XKB_KEY_Left, XKB_KEY_Left},
    {xkbKeycode(KEY_RIGHT), XKB_KEY_Right, XKB_KEY_Right},
    {xkbKeycode(KEY_HOME), XKB_KEY_Home, XKB_KEY_Home},
    {xkbKeycode(KEY_END), XKB_KEY_End, XKB_KEY_End},
    {xkbKeycode(KEY_PAGEUP), XKB_KEY_Page_Up, XKB_KEY_Page_Up},
    {xkbKeycode(KEY_PAGEDOWN), XKB_KEY_Page_Down, XKB_KEY_Page_Down},
    {xkbKeycode(KEY_INSERT), XKB_KEY_Insert, XKB_KEY_Insert},
    {xkbKeycode(KEY_LEFTSHIFT), XKB_KEY_Shift_L, XKB_KEY_Shift_L},
    {xkbKeycode(KEY_RIGHTSHIFT), XKB_KEY_Shift_R, XKB_KEY_Shift_R},
    {xkbKeycode(KEY_LEFTCTRL), XKB_KEY_Control_L, XKB_KEY_Control_L},
    {xkbKeycode(KEY_RIGHTCTRL), XKB_KEY_Control_R, XKB_KEY_Control_R},
    {xkbKeycode(KEY_LEFTALT), XKB_KEY_Alt_L, XKB_KEY_Alt_L},
    {xkbKeycode(KEY_RIGHTALT), XKB_KEY_Alt_R, XKB_KEY_Alt_R},
    {xkbKeycode(KEY_LEFTMETA), XKB_KEY_Meta_L, XKB_KEY_Meta_L},
    {xkbKeycode(KEY_RIGHTMETA), XKB_KEY_Meta_R, XKB_KEY_Meta_R},
    {xkbKeycode(KEY_CAPSLOCK), XKB_KEY_Caps_Lock, XKB_KEY_Caps_Lock},
    {xkbKeycode(KEY_F1), XKB_KEY_F1, XKB_KEY_F1},
    {xkbKeycode(KEY_F2), XKB_KEY_F2, XKB_KEY_F2},
    {xkbKeycode(KEY_F3), XKB_KEY_F3, XKB_KEY_F3},
    {xkbKeycode(KEY_F4), XKB_KEY_F4, XKB_KEY_F4},
    {xkbKeycode(KEY_F5), XKB_KEY_F5, XKB_KEY_F5},
    {xkbKeycode(KEY_F6), XKB_KEY_F6, XKB_KEY_F6},
    {xkbKeycode(KEY_F7), XKB_KEY_F7, XKB_KEY_F7},
    {xkbKeycode(KEY_F8), XKB_KEY_F8, XKB_KEY_F8},
    {xkbKeycode(KEY_F9), XKB_KEY_F9, XKB_KEY_F9},
    {xkbKeycode(KEY_F10), XKB_KEY_F10, XKB_KEY_F10},
    {xkbKeycode(KEY_F11), XKB_KEY_F11, XKB_KEY_F11},
    {xkbKeycode(KEY_F12), XKB_KEY_F12, XKB_KEY_F12},

    {xkbKeycode(KEY_KP0), XKB_KEY_KP_0, XKB_KEY_KP_Insert},
    {xkbKeycode(KEY_KP1), XKB_KEY_KP_1, XKB_KEY_KP_End},
    {xkbKeycode(KEY_KP2), XKB_KEY_KP_2, XKB_KEY_KP_Down},
    {xkbKeycode(KEY_KP3), XKB_KEY_KP_3, XKB_KEY_KP_Page_Down},
    {xkbKeycode(KEY_KP4), XKB_KEY_KP_4, XKB_KEY_KP_Left},
    {xkbKeycode(KEY_KP5), XKB_KEY_KP_5, XKB_KEY_KP_Begin},
    {xkbKeycode(KEY_KP6), XKB_KEY_KP_6, XKB_KEY_KP_Right},
    {xkbKeycode(KEY_KP7), XKB_KEY_KP_7, XKB_KEY_KP_Home},
    {xkbKeycode(KEY_KP8), XKB_KEY_KP_8, XKB_KEY_KP_Up},
    {xkbKeycode(KEY_KP9), XKB_KEY_KP_9, XKB_KEY_KP_Page_Up},
    {xkbKeycode(KEY_KPDOT), XKB_KEY_KP_Decimal, XKB_KEY_KP_Delete},
    {xkbKeycode(KEY_KPPLUS), XKB_KEY_KP_Add, XKB_KEY_KP_Add},
    {xkbKeycode(KEY_KPMINUS), XKB_KEY_KP_Subtract, XKB_KEY_KP_Subtract},
    {xkbKeycode(KEY_KPASTERISK), XKB_KEY_KP_Multiply, XKB_KEY_KP_Multiply},
    {xkbKeycode(KEY_KPSLASH), XKB_KEY_KP_Divide, XKB_KEY_KP_Divide},
    {xkbKeycode(KEY_KPENTER), XKB_KEY_KP_Enter, XKB_KEY_KP_Enter},

    {xkbKeycode(KEY_PAUSE), XKB_KEY_Pause, XKB_KEY_Pause},
    {xkbKeycode(KEY_HELP), XKB_KEY_Help, XKB_KEY_Help},
    {xkbKeycode(KEY_CANCEL), XKB_KEY_Cancel, XKB_KEY_Cancel},
    {xkbKeycode(KEY_NUMLOCK), XKB_KEY_Num_Lock, XKB_KEY_Num_Lock},
    {xkbKeycode(KEY_SCROLLLOCK), XKB_KEY_Scroll_Lock, XKB_KEY_Scroll_Lock},
};

static const KeymapKey* findKey(uint32_t keycode)
{
    for (const auto& key : s_keys) {
        if (key.keycode == keycode)
            return &key;
    }
    return nullptr;
}

static bool isLetter(const KeymapKey& key)
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

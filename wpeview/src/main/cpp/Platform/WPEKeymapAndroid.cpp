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

static gboolean wpeKeymapAndroidGetEntriesForKeyval(
    WPEKeymap* keymap, guint keyval, WPEKeymapEntry** entries, guint* nEntries)
{
    UNUSED_PARAM(keymap);
    UNUSED_PARAM(keyval);
    if (entries)
        *entries = nullptr;
    if (nEntries)
        *nEntries = 0;
    return FALSE;
}

static gboolean wpeKeymapAndroidTranslateKeyboardState(WPEKeymap* keymap, guint hardware_keycode,
    WPEModifiers modifiers, int group, guint* keyval, int* effectiveGroup, int* level, WPEModifiers* consumed)
{
    UNUSED_PARAM(keymap);
    UNUSED_PARAM(hardware_keycode);
    UNUSED_PARAM(modifiers);
    UNUSED_PARAM(group);
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

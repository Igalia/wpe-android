/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
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

#pragma once

/**
 * Android to XKB keycode/keysym mapping utilities.
 *
 * WPE Platform expects XKB-style keycodes and keysyms for keyboard events, but Android
 * uses its own keycode system (AKEYCODE_*). This header provides the necessary mappings:
 *
 * - androidToXkbKeycode(): Converts Android keycodes to XKB keycodes (Linux evdev + 8)
 * - androidToKeysym(): Converts Android keycode + unicode to XKB keysym
 * - androidToWpeModifiers(): Converts Android meta state to WPE modifier flags
 */

#include <android/input.h>
#include <android/keycodes.h>
#include <linux/input-event-codes.h>
#include <wpe/wpe-platform.h>
#include <xkbcommon/xkbcommon-keysyms.h>

/**
 * Convert Android keycode to XKB keycode (Linux input code + 8).
 * Returns 0 if the keycode is not mapped.
 */
inline uint32_t androidToXkbKeycode(int androidKeyCode)
{
    // Map Android keycodes to Linux input codes, then add 8 for XKB compatibility
    switch (androidKeyCode) {
    // Letters A-Z (Android 29-54 -> Linux 30-55)
    case AKEYCODE_A:
        return KEY_A + 8;
    case AKEYCODE_B:
        return KEY_B + 8;
    case AKEYCODE_C:
        return KEY_C + 8;
    case AKEYCODE_D:
        return KEY_D + 8;
    case AKEYCODE_E:
        return KEY_E + 8;
    case AKEYCODE_F:
        return KEY_F + 8;
    case AKEYCODE_G:
        return KEY_G + 8;
    case AKEYCODE_H:
        return KEY_H + 8;
    case AKEYCODE_I:
        return KEY_I + 8;
    case AKEYCODE_J:
        return KEY_J + 8;
    case AKEYCODE_K:
        return KEY_K + 8;
    case AKEYCODE_L:
        return KEY_L + 8;
    case AKEYCODE_M:
        return KEY_M + 8;
    case AKEYCODE_N:
        return KEY_N + 8;
    case AKEYCODE_O:
        return KEY_O + 8;
    case AKEYCODE_P:
        return KEY_P + 8;
    case AKEYCODE_Q:
        return KEY_Q + 8;
    case AKEYCODE_R:
        return KEY_R + 8;
    case AKEYCODE_S:
        return KEY_S + 8;
    case AKEYCODE_T:
        return KEY_T + 8;
    case AKEYCODE_U:
        return KEY_U + 8;
    case AKEYCODE_V:
        return KEY_V + 8;
    case AKEYCODE_W:
        return KEY_W + 8;
    case AKEYCODE_X:
        return KEY_X + 8;
    case AKEYCODE_Y:
        return KEY_Y + 8;
    case AKEYCODE_Z:
        return KEY_Z + 8;

    // Numbers 0-9
    case AKEYCODE_0:
        return KEY_0 + 8;
    case AKEYCODE_1:
        return KEY_1 + 8;
    case AKEYCODE_2:
        return KEY_2 + 8;
    case AKEYCODE_3:
        return KEY_3 + 8;
    case AKEYCODE_4:
        return KEY_4 + 8;
    case AKEYCODE_5:
        return KEY_5 + 8;
    case AKEYCODE_6:
        return KEY_6 + 8;
    case AKEYCODE_7:
        return KEY_7 + 8;
    case AKEYCODE_8:
        return KEY_8 + 8;
    case AKEYCODE_9:
        return KEY_9 + 8;

    // Special keys
    case AKEYCODE_ENTER:
        return KEY_ENTER + 8;
    case AKEYCODE_DEL:
        return KEY_BACKSPACE + 8;
    case AKEYCODE_FORWARD_DEL:
        return KEY_DELETE + 8;
    case AKEYCODE_ESCAPE:
        return KEY_ESC + 8;
    case AKEYCODE_TAB:
        return KEY_TAB + 8;
    case AKEYCODE_SPACE:
        return KEY_SPACE + 8;

    // Navigation
    case AKEYCODE_DPAD_UP:
        return KEY_UP + 8;
    case AKEYCODE_DPAD_DOWN:
        return KEY_DOWN + 8;
    case AKEYCODE_DPAD_LEFT:
        return KEY_LEFT + 8;
    case AKEYCODE_DPAD_RIGHT:
        return KEY_RIGHT + 8;
    case AKEYCODE_MOVE_HOME:
        return KEY_HOME + 8;
    case AKEYCODE_MOVE_END:
        return KEY_END + 8;
    case AKEYCODE_PAGE_UP:
        return KEY_PAGEUP + 8;
    case AKEYCODE_PAGE_DOWN:
        return KEY_PAGEDOWN + 8;
    case AKEYCODE_INSERT:
        return KEY_INSERT + 8;

    // Modifiers
    case AKEYCODE_SHIFT_LEFT:
        return KEY_LEFTSHIFT + 8;
    case AKEYCODE_SHIFT_RIGHT:
        return KEY_RIGHTSHIFT + 8;
    case AKEYCODE_CTRL_LEFT:
        return KEY_LEFTCTRL + 8;
    case AKEYCODE_CTRL_RIGHT:
        return KEY_RIGHTCTRL + 8;
    case AKEYCODE_ALT_LEFT:
        return KEY_LEFTALT + 8;
    case AKEYCODE_ALT_RIGHT:
        return KEY_RIGHTALT + 8;
    case AKEYCODE_META_LEFT:
        return KEY_LEFTMETA + 8;
    case AKEYCODE_META_RIGHT:
        return KEY_RIGHTMETA + 8;
    case AKEYCODE_CAPS_LOCK:
        return KEY_CAPSLOCK + 8;

    // Function keys
    case AKEYCODE_F1:
        return KEY_F1 + 8;
    case AKEYCODE_F2:
        return KEY_F2 + 8;
    case AKEYCODE_F3:
        return KEY_F3 + 8;
    case AKEYCODE_F4:
        return KEY_F4 + 8;
    case AKEYCODE_F5:
        return KEY_F5 + 8;
    case AKEYCODE_F6:
        return KEY_F6 + 8;
    case AKEYCODE_F7:
        return KEY_F7 + 8;
    case AKEYCODE_F8:
        return KEY_F8 + 8;
    case AKEYCODE_F9:
        return KEY_F9 + 8;
    case AKEYCODE_F10:
        return KEY_F10 + 8;
    case AKEYCODE_F11:
        return KEY_F11 + 8;
    case AKEYCODE_F12:
        return KEY_F12 + 8;

    // Punctuation and symbols
    case AKEYCODE_MINUS:
        return KEY_MINUS + 8;
    case AKEYCODE_EQUALS:
        return KEY_EQUAL + 8;
    case AKEYCODE_LEFT_BRACKET:
        return KEY_LEFTBRACE + 8;
    case AKEYCODE_RIGHT_BRACKET:
        return KEY_RIGHTBRACE + 8;
    case AKEYCODE_BACKSLASH:
        return KEY_BACKSLASH + 8;
    case AKEYCODE_SEMICOLON:
        return KEY_SEMICOLON + 8;
    case AKEYCODE_APOSTROPHE:
        return KEY_APOSTROPHE + 8;
    case AKEYCODE_GRAVE:
        return KEY_GRAVE + 8;
    case AKEYCODE_COMMA:
        return KEY_COMMA + 8;
    case AKEYCODE_PERIOD:
        return KEY_DOT + 8;
    case AKEYCODE_SLASH:
        return KEY_SLASH + 8;

    default:
        return 0;
    }
}

/**
 * Convert Android keycode and unicode character to XKB keysym.
 * For printable ASCII characters, the unicode value equals the keysym.
 * For special keys, returns the appropriate XKB keysym.
 */
inline uint32_t androidToKeysym(int androidKeyCode, int unicodeChar)
{
    // For printable ASCII characters, unicode value IS the keysym
    if (unicodeChar >= 0x20 && unicodeChar <= 0x7f)
        return static_cast<uint32_t>(unicodeChar);

    // For special keys, return the XKB keysym
    switch (androidKeyCode) {
    case AKEYCODE_ENTER:
        return XKB_KEY_Return;
    case AKEYCODE_DEL:
        return XKB_KEY_BackSpace;
    case AKEYCODE_FORWARD_DEL:
        return XKB_KEY_Delete;
    case AKEYCODE_ESCAPE:
        return XKB_KEY_Escape;
    case AKEYCODE_TAB:
        return XKB_KEY_Tab;

    case AKEYCODE_DPAD_UP:
        return XKB_KEY_Up;
    case AKEYCODE_DPAD_DOWN:
        return XKB_KEY_Down;
    case AKEYCODE_DPAD_LEFT:
        return XKB_KEY_Left;
    case AKEYCODE_DPAD_RIGHT:
        return XKB_KEY_Right;
    case AKEYCODE_MOVE_HOME:
        return XKB_KEY_Home;
    case AKEYCODE_MOVE_END:
        return XKB_KEY_End;
    case AKEYCODE_PAGE_UP:
        return XKB_KEY_Page_Up;
    case AKEYCODE_PAGE_DOWN:
        return XKB_KEY_Page_Down;
    case AKEYCODE_INSERT:
        return XKB_KEY_Insert;

    case AKEYCODE_SHIFT_LEFT:
        return XKB_KEY_Shift_L;
    case AKEYCODE_SHIFT_RIGHT:
        return XKB_KEY_Shift_R;
    case AKEYCODE_CTRL_LEFT:
        return XKB_KEY_Control_L;
    case AKEYCODE_CTRL_RIGHT:
        return XKB_KEY_Control_R;
    case AKEYCODE_ALT_LEFT:
        return XKB_KEY_Alt_L;
    case AKEYCODE_ALT_RIGHT:
        return XKB_KEY_Alt_R;
    case AKEYCODE_META_LEFT:
        return XKB_KEY_Meta_L;
    case AKEYCODE_META_RIGHT:
        return XKB_KEY_Meta_R;
    case AKEYCODE_CAPS_LOCK:
        return XKB_KEY_Caps_Lock;

    case AKEYCODE_F1:
        return XKB_KEY_F1;
    case AKEYCODE_F2:
        return XKB_KEY_F2;
    case AKEYCODE_F3:
        return XKB_KEY_F3;
    case AKEYCODE_F4:
        return XKB_KEY_F4;
    case AKEYCODE_F5:
        return XKB_KEY_F5;
    case AKEYCODE_F6:
        return XKB_KEY_F6;
    case AKEYCODE_F7:
        return XKB_KEY_F7;
    case AKEYCODE_F8:
        return XKB_KEY_F8;
    case AKEYCODE_F9:
        return XKB_KEY_F9;
    case AKEYCODE_F10:
        return XKB_KEY_F10;
    case AKEYCODE_F11:
        return XKB_KEY_F11;
    case AKEYCODE_F12:
        return XKB_KEY_F12;

    default:
        // For unknown keys, return the unicode char if available
        if (unicodeChar > 0)
            return static_cast<uint32_t>(unicodeChar);
        return 0;
    }
}

/**
 * Convert Android KeyEvent meta state to WPE modifiers.
 */
inline WPEModifiers androidToWpeModifiers(int metaState)
{
    uint32_t mods = 0;
    if (metaState & AMETA_CTRL_ON)
        mods |= WPE_MODIFIER_KEYBOARD_CONTROL;
    if (metaState & AMETA_SHIFT_ON)
        mods |= WPE_MODIFIER_KEYBOARD_SHIFT;
    if (metaState & AMETA_ALT_ON)
        mods |= WPE_MODIFIER_KEYBOARD_ALT;
    if (metaState & AMETA_META_ON)
        mods |= WPE_MODIFIER_KEYBOARD_META;
    if (metaState & AMETA_CAPS_LOCK_ON)
        mods |= WPE_MODIFIER_KEYBOARD_CAPS_LOCK;
    return static_cast<WPEModifiers>(mods);
}

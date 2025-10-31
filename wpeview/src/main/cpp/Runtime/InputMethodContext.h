/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include <functional>
#include <wpe/webkit.h>

class InputMethodContextObserver {
public:
    InputMethodContextObserver() = default;
    virtual ~InputMethodContextObserver() = default;

    InputMethodContextObserver(InputMethodContextObserver&&) = default;
    InputMethodContextObserver& operator=(InputMethodContextObserver&&) = default;
    InputMethodContextObserver(const InputMethodContextObserver&) = default;
    InputMethodContextObserver& operator=(const InputMethodContextObserver&) = default;

    virtual void onInputMethodContextIn() noexcept = 0;
    virtual void onInputMethodContextOut() noexcept = 0;
};

class InputMethodContext final {
public:
    InputMethodContext(InputMethodContextObserver* observer);

    WebKitInputMethodContext* webKitInputMethodContext() const noexcept { return m_webKitInputMethodContext.get(); }

    void setContent(const char* utf8Content) const noexcept;
    void deleteContent(int offset, int count) const noexcept;

private:
    InputMethodContextObserver* m_observer;

    template <typename T> using ProtectedUniquePointer = std::unique_ptr<T, std::function<void(T*)>>;
    ProtectedUniquePointer<WebKitInputMethodContext> m_webKitInputMethodContext {};
};

package ru.mcs;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        // GUI-режим.
        // Позже сюда можно добавить CLI, как в stegobox.
        FlatLightLaf.setup();

        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);

        SwingUtilities.invokeLater(() -> new AudioFrame().setVisible(true));
    }
}
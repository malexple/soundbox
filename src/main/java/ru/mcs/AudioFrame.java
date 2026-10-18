package ru.mcs;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.sound.sampled.AudioFormat;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.Arrays;

public class AudioFrame extends JFrame {

    private final JTextField pathField = new JTextField(30);
    private final JTextArea textArea = new JTextArea(12, 48);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JLabel statusLabel = new JLabel("Готов");

    private final JButton browseBtn = new JButton("Обзор...");
    private final JButton hideBtn = new JButton("Скрыть текст в аудио");
    private final JButton extractBtn = new JButton("Извлечь текст из аудио");

    private File selectedFile;
    private AudioFormat pcmFormat;
    private byte[] pcmData;

    public AudioFrame() {
        super("SoundBox — LSB-стеганография в WAV + AES-256");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        initUI();

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // --- Top: file selection
        JPanel filePanel = new JPanel(new BorderLayout(8, 0));
        filePanel.add(new JLabel("Аудиофайл:"), BorderLayout.WEST);

        pathField.setEditable(false);
        filePanel.add(pathField, BorderLayout.CENTER);
        filePanel.add(browseBtn, BorderLayout.EAST);

        // --- Center: text + password
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(new JLabel("Текст для сокрытия / Извлечённый текст:"), BorderLayout.NORTH);

        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        center.add(new JScrollPane(textArea), BorderLayout.CENTER);

        JPanel passPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        passPanel.add(new JLabel("Пароль:"));
        passPanel.add(passwordField);
        passPanel.add(new JLabel("(AES-256 / PBKDF2 / GCM)"));
        center.add(passPanel, BorderLayout.SOUTH);

        // --- Bottom: buttons + status
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.add(hideBtn);
        buttons.add(extractBtn);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(statusLabel, BorderLayout.SOUTH);

        root.add(filePanel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);

        browseBtn.addActionListener(e -> chooseFile());
        hideBtn.addActionListener(e -> doHide());
        extractBtn.addActionListener(e -> doExtract());

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateFill();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateFill();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateFill();
            }
        });
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(
                "Выберите WAV-файл. MP3 лучше заранее сконвертировать в WAV PCM 16-bit"
        );
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("WAV-аудио (*.wav)", "wav"));

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();

        try {
            AudioEngine.PcmAudio pcm = AudioEngine.loadPcm(file);

            selectedFile = file;
            pcmFormat = pcm.format();
            pcmData = pcm.data();

            pathField.setText(file.getAbsolutePath());

            long cap = AudioEngine.capacityBytes(pcmData);
            statusLabel.setText(String.format(
                    "Загружено: %s, ёмкость ≈ %s",
                    file.getName(),
                    AudioEngine.humanBytes(cap)
            ));

            updateFill();
        } catch (Exception ex) {
            showError(
                    "Не удалось прочитать аудио. Сейчас лучше всего использовать WAV/PCM.\n" +
                            "Ошибка: " + ex.getMessage()
            );
        }
    }

    private void updateFill() {
        if (pcmData == null) {
            return;
        }

        String text = textArea.getText();
        long cap = AudioEngine.capacityBytes(pcmData);

        if (text.isEmpty()) {
            statusLabel.setText(
                    "Ёмкость ≈ " + AudioEngine.humanBytes(cap) +
                            ". Введите текст для сокрытия."
            );
            return;
        }

        long need = AudioEngine.payloadSize(text);
        double pct = cap == 0 ? 0 : 100.0 * need / cap;

        if (need > cap) {
            statusLabel.setText(String.format(
                    "Заполнение: %s / %s (%.1f%%) — не поместится!",
                    AudioEngine.humanBytes(need),
                    AudioEngine.humanBytes(cap),
                    pct
            ));
        } else {
            statusLabel.setText(String.format(
                    "Заполнение: %s / %s (%.1f%%)",
                    AudioEngine.humanBytes(need),
                    AudioEngine.humanBytes(cap),
                    pct
            ));
        }
    }

    private void doHide() {
        if (pcmData == null) {
            showError("Сначала выберите аудиофайл");
            return;
        }

        String text = textArea.getText();
        if (text == null || text.isEmpty()) {
            showError("Введите текст для сокрытия");
            return;
        }

        char[] password = passwordField.getPassword();
        if (password.length == 0) {
            showError("Введите пароль");
            return;
        }

        long need = AudioEngine.payloadSize(text);
        long cap = AudioEngine.capacityBytes(pcmData);

        if (need > cap) {
            JOptionPane.showMessageDialog(
                    this,
                    String.format(
                            "Текст не поместится в аудио.%n%n" +
                                    "Нужно: %s%n" +
                                    "Доступно: %s%n%n" +
                                    "Возьмите более длинный файл или сократите текст.",
                            AudioEngine.humanBytes(need),
                            AudioEngine.humanBytes(cap)
                    ),
                    "Недостаточно места",
                    JOptionPane.WARNING_MESSAGE
            );

            statusLabel.setText("Недостаточно места");
            return;
        }

        setBusy(true);
        statusLabel.setText("Шифрование и встраивание...");

        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                byte[] stegoData = AudioEngine.hideText(pcmData, text, password);
                File outFile = AudioEngine.buildOutputFile(selectedFile);
                AudioEngine.writeWav(outFile, pcmFormat, stegoData);
                return outFile;
            }

            @Override
            protected void done() {
                Arrays.fill(password, '\0');
                setBusy(false);

                try {
                    File out = get();
                    statusLabel.setText("Готово. Сохранено: " + out.getAbsolutePath());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    showError("Ошибка: " + cause.getMessage());
                    statusLabel.setText("Ошибка");
                }
            }
        }.execute();
    }

    private void doExtract() {
        if (pcmData == null) {
            showError("Сначала выберите аудиофайл");
            return;
        }

        char[] password = passwordField.getPassword();
        if (password.length == 0) {
            showError("Введите пароль");
            return;
        }

        setBusy(true);
        statusLabel.setText("Извлечение и расшифровка...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return AudioEngine.extractText(pcmData, password);
            }

            @Override
            protected void done() {
                Arrays.fill(password, '\0');
                setBusy(false);

                try {
                    String text = get();
                    textArea.setText(text);
                    statusLabel.setText("Готово. Извлечено " + text.length() + " символов");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    showError("Не удалось извлечь: " + cause.getMessage());
                    statusLabel.setText("Ошибка");
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        browseBtn.setEnabled(!busy);
        hideBtn.setEnabled(!busy);
        extractBtn.setEnabled(!busy);
        passwordField.setEnabled(!busy);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }
}
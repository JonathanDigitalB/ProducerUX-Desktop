package com.jonathan.producerux.controller;

import com.jonathan.producerux.MainApp;
import com.jonathan.producerux.model.DownloadModel;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;

public class VideoController {

    @FXML
    private TextField linkField;
    @FXML
    private TextField nameField;
    @FXML
    private RadioButton rb1080;
    @FXML
    private RadioButton rb720;
    @FXML
    private RadioButton rbNameCustom;
    @FXML
    private Button downloadButton;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLabel;

    private final DownloadModel model = new DownloadModel();

    @FXML
    private void initialize() {
        nameField.visibleProperty().bind(rbNameCustom.selectedProperty());
        nameField.managedProperty().bind(rbNameCustom.selectedProperty());
        showProgress(false);
    }

    private void showProgress(boolean visible) {
        progressBar.setVisible(visible);
        progressBar.setManaged(visible);
    }

    @FXML
    private void onDownload() {
        String raw = linkField.getText().trim();
        if (raw.isEmpty()) {
            Dialogs.info("Falta el enlace", "Pega el enlace del video en el campo de texto antes de pulsar Descargar.");
            return;
        }

        String link = DownloadModel.normalizeLink(raw);
        if (link == null) {
            Dialogs.info("Enlace no válido",
                    "Lo que pegaste no parece un enlace web: \"" + DownloadModel.shorten(raw, 120)
                    + "\".\n\nCopia el enlace completo del video o publicación. Debe empezar con https:// y no puede tener espacios.");
            return;
        }

        int quality = DownloadModel.QUALITY_ORIGINAL;
        if (rb1080.isSelected()) {
            quality = DownloadModel.QUALITY_1080;
        } else if (rb720.isSelected()) {
            quality = DownloadModel.QUALITY_720;
        }

        String name = null;
        if (rbNameCustom.isSelected()) {
            name = nameField.getText();
            if (name.trim().isEmpty()) {
                Dialogs.info("Falta el nombre", "Elegiste nombre personalizado pero el campo está vacío. Escribe un nombre o elige la opción de nombre original.");
                return;
            }
        }

        downloadButton.setDisable(true);
        progressBar.setProgress(0);
        showProgress(true);
        statusLabel.setText("Descargando...");

        model.download(link, false, quality, name, new DownloadModel.Listener() {
            @Override
            public void onProgress(int percent) {
                Platform.runLater(() -> {
                    int value = Math.max(percent, 0);
                    progressBar.setProgress(value / 100.0);
                    statusLabel.setText("Descargando... " + value + "%");
                });
            }

            @Override
            public void onSuccess(String message) {
                Platform.runLater(() -> {
                    showProgress(false);
                    downloadButton.setDisable(false);
                    statusLabel.setText("Guardado en: " + message);
                });
            }

            @Override
            public void onWarning(String title, String message) {
                Platform.runLater(() -> Dialogs.info(title, message));
            }

            @Override
            public void onError(String title, String message) {
                Platform.runLater(() -> {
                    showProgress(false);
                    downloadButton.setDisable(false);
                    statusLabel.setText("");
                    Dialogs.info(title, message);
                });
            }
        });
    }

    @FXML
    private void onBack() {
        MainApp.goTo("main");
    }
}

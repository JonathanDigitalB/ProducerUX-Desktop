package com.jonathan.producerux.controller;

import com.jonathan.producerux.MainApp;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.Region;

public final class Dialogs {

    private Dialogs() {
    }

    public static void info(String header, String message) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle("producerUX");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setPrefWidth(560);
        if (MainApp.getStage() != null) {
            alert.initOwner(MainApp.getStage());
        }
        alert.showAndWait();
    }
}

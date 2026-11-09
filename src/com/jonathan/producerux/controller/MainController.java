package com.jonathan.producerux.controller;

import com.jonathan.producerux.MainApp;

import javafx.application.Platform;
import javafx.fxml.FXML;

public class MainController {

    @FXML
    private void openVideo() {
        MainApp.goTo("video");
    }

    @FXML
    private void openAudio() {
        MainApp.goTo("audio");
    }

    @FXML
    private void exitApp() {
        Platform.exit();
        System.exit(0);
    }
}

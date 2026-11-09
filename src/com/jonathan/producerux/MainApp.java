package com.jonathan.producerux;

import com.jonathan.producerux.controller.Dialogs;
import com.jonathan.producerux.model.DownloadModel;

import java.io.IOException;
import java.io.InputStream;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MainApp extends Application {

    private static Stage stage;
    private static Scene scene;

    @Override
    public void start(Stage primaryStage) throws Exception {
        stage = primaryStage;
        stage.setTitle("producerUX");
        stage.setMinWidth(480);
        stage.setMinHeight(700);

        InputStream icon = MainApp.class.getResourceAsStream("/resources/view/icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon));
        }

        scene = new Scene(loadView("main"), 480, 700);
        scene.getStylesheets().add(MainApp.class.getResource("/resources/view/style.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        DownloadModel.prepareInBackground();
    }

    public static Stage getStage() {
        return stage;
    }

    public static void goTo(String view) {
        try {
            scene.setRoot(loadView(view));
        } catch (Exception e) {
            Dialogs.info("No se pudo abrir la pantalla",
                    "La pantalla \"" + view + "\" no se pudo cargar. Esto pasa si falta el archivo "
                    + view + ".fxml dentro de la carpeta resources/view o si tiene un error.\n\nDetalle técnico:\n"
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static Parent loadView(String view) throws IOException {
        return FXMLLoader.load(MainApp.class.getResource("/resources/view/" + view + ".fxml"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}

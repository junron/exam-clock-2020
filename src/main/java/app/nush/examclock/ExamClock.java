package app.nush.examclock;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.prefs.Preferences;

public class ExamClock extends Application {
    public static Preferences preferences;
    private MainController controller;
    private static ExamClock instance;

    public static void main(String[] args) {
        launch(args);
    }

    public static ExamClock getInstance() {
        return instance;
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        instance = this;
        preferences = Preferences.userNodeForPackage(ExamClock.class);
        URL resource = getClass().getResource("./res/fxml_main.fxml");
        System.out.println(resource);
        FXMLLoader loader = new FXMLLoader(resource);
        Parent root = loader.load();
        controller = loader.getController();
        controller.setStage(primaryStage);
        Scene scene = new Scene(root);
        scene.getStylesheets().add("/app/nush/examclock/res/main.css");
        scene.getStylesheets().add("/app/nush/examclock/res/theme.css");
        scene.getStylesheets().add(PreferenceController.nightMode.get() ? "/app/nush/examclock/res/theme.dark.css" : "/app/nush/examclock/res/theme.light.css");
        primaryStage.titleProperty().bind(Bindings.concat("Exam Clock " + Version.getVersion() + " : ", PreferenceController.connectivityStateProperty));

        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            System.out.println("Closing Down...");
            controller.onClose(event);
            System.exit(0);
        });
        primaryStage.show();
    }
}
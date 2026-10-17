/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Where the operator is in the registration, as a vertical list: done, now, next. The state is written as a word as
 * well as shown by colour and a tick, and each row reads as one sentence to a screen reader.
 */
public final class StepRail extends VBox {

    public enum State { TODO, CURRENT, DONE }

    /** One row; the caller supplies every word, so the rail itself holds no language. */
    public static final class Step {
        private final String number;
        private final String title;
        private final String stateWord;
        private final State state;

        public Step(String number, String title, String stateWord, State state) {
            this.number = number;
            this.title = title;
            this.stateWord = stateWord;
            this.state = state;
        }

        public String number() {
            return number;
        }

        public String title() {
            return title;
        }

        public String stateWord() {
            return stateWord;
        }

        public State state() {
            return state;
        }
    }

    private final Label heading = new Label();

    public StepRail() {
        getStyleClass().add("rail");
        heading.getStyleClass().add("rail-heading");
        setMinWidth(230);
        setPrefWidth(230);
        setMaxWidth(230);
        setFocusTraversable(false);
    }

    public void update(String headingText, List<Step> steps) {
        heading.setText(headingText);
        getChildren().setAll(heading);
        for (Step s : steps) {
            getChildren().add(row(s));
        }
    }

    private static HBox row(Step s) {
        StackPane badge = new StackPane();
        badge.getStyleClass().add("step-badge");
        badge.setMaxSize(30, 30); // a title that wraps to two lines must not stretch the badge into a pill
        if (s.state() == State.DONE) {
            StackPane tick = Icons.CHECK.node(16);
            tick.getStyleClass().add("icon-on-dark");
            badge.getChildren().add(tick);
        } else {
            badge.getChildren().add(new Label(s.number()));
        }
        Label title = new Label(s.title());
        title.getStyleClass().add("step-title");
        title.setWrapText(true);
        Label state = new Label(s.stateWord());
        state.getStyleClass().add("step-state");
        VBox words = new VBox(1, title, state);
        HBox row = new HBox(12, badge, words);
        row.getStyleClass().add("step");
        row.getStyleClass().add(s.state() == State.CURRENT ? "step-current" : s.state() == State.DONE ? "step-done" : "step-todo");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setAccessibleText(s.number() + ". " + s.title() + ". " + s.stateWord());
        return row;
    }
}

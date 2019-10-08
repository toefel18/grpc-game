package nl.toefel.client.view;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableMap;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import nl.toefel.grpc.game.TicTacToeOuterClass.BoardMove;
import nl.toefel.grpc.game.TicTacToeOuterClass.GameEvent;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;
import static nl.toefel.grpc.game.TicTacToeOuterClass.Player;

public class GamesTabComponent extends TabPane {

  private final SimpleObjectProperty<Player> myself;

  public GamesTabComponent(SimpleObjectProperty<Player> myself,
                           ObservableMap<String, GameEvent> gameStates,
                           Consumer<BoardMove> moveCommandCallback) {
      this.myself = myself;
        gameStates.addListener((InvalidationListener) x -> {
            Platform.runLater(() -> {
                updateTabs(gameStates, moveCommandCallback);
            });
        });
    }

    private void updateTabs(ObservableMap<String, GameEvent> gameStates, Consumer<BoardMove> moveCommandCallback) {
        for (GameEvent event : gameStates.values()) {
            Tab tab = getOrCreateTab(event.getGameId());
            GameComponent gameComponent = new GameComponent(myself, event, moveCommandCallback);
            tab.setContent(gameComponent);
        }
    }

  private Tab getOrCreateTab(String gameId) {
    Map<String, Tab> tabsById = getTabs().stream().collect(toMap(Tab::getText, Function.identity()));
    if (tabsById.containsKey(gameId)) {
      return tabsById.get(gameId);
    }
    Tab tab = new Tab(gameId);
    getTabs().add(tab);
    return tab;
  }
}
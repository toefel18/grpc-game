package nl.toefel.tictactoe.server.state;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import nl.toefel.grpc.game.TicTacToeOuterClass.*;
import nl.toefel.tictactoe.server.auth.Contexts;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static nl.toefel.grpc.game.TicTacToeOuterClass.*;

public class ServerState {

    private AtomicInteger playerIdSequence = new AtomicInteger(1);
    private AtomicInteger gameIdSequence = new AtomicInteger(1);

    // a list of all players ever created during runtime
    private List<Player> createdPlayers = new ArrayList<>();

    // List of tuples with players still connected via the PlayGame method and their input/output stream.
    private List<PlayerWithIO> joinedPlayers = new ArrayList<>();

    // Maps gameId to GameEvent, a game event contains the latest state of a game
    private Map<String, GameEvent> games = new HashMap<>();

    public List<Player> getJoinedPlayers() {
        return joinedPlayers.stream()
            .map(PlayerWithIO::getPlayer)
            .collect(Collectors.toList());
    }

    public Player createNewPlayer(String name) {
        Player newPlayer = Player.newBuilder()
            .setId(String.valueOf(playerIdSequence.getAndIncrement()))
            .setName(name)
            .setJoinTimestamp(System.currentTimeMillis())
            .setWins(0)
            .build();

        createdPlayers.add(newPlayer);

        return newPlayer;
    }

    public void joinPlayerAndTrack(StreamObserver<GameCommand> gameCommandStream, StreamObserver<GameEvent> responseObserver) {
        String playerId = Contexts.PLAYER_ID.get();
        Optional<PlayerWithIO> existingJoinedPlayer = findPlayerWithIOPlayerId(playerId);
        Optional<Player> playerToBeJoined = createdPlayers.stream().filter(it -> Objects.equals(it.getId(), playerId)).findFirst();

        if (existingJoinedPlayer.isPresent()) {
            // onError closes the stream
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription("player with id " + playerId + " has already joined!").asException());
        } else if (playerToBeJoined.isEmpty()) {
            // onError closes the stream
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription("player with id " + playerId + " does not exist, create player first!!").asException());
        } else {
            PlayerWithIO playerWithIO = new PlayerWithIO(playerToBeJoined.get(), responseObserver);
            this.joinedPlayers.add(playerWithIO);
        }
    }

    public void unjoinPlayer() {
        String playerId = Contexts.PLAYER_ID.get();
        Optional<PlayerWithIO> existingJoinedPlayer = findPlayerWithIOPlayerId(playerId);
        if (existingJoinedPlayer.isPresent()) {
            PlayerWithIO playerWithIO = existingJoinedPlayer.get();
            log("Unjoining player " + playerId);
            joinedPlayers.remove(playerWithIO);
            closeActiveGamesOfUnjoinedPlayer(playerWithIO);
        } else {
            log("Unjoining player " + playerId + ", but player does not exist anymore");
        }
    }

    private void closeActiveGamesOfUnjoinedPlayer(PlayerWithIO playerWithIO) {
        games.values().stream()
            .filter(game -> eq(game.getPlayerX(), playerWithIO.getPlayer()) || eq(game.getPlayerO(), playerWithIO.getPlayer()))
            .collect(Collectors.toList()) // to avoid concurrent
            .forEach(game -> closeGame(game, playerWithIO));
    }

    private void closeGame(GameEvent game, PlayerWithIO playerWithIO) {
        Player opponent = eq(game.getPlayerO(), playerWithIO.getPlayer()) ? game.getPlayerX() : game.getPlayerO();
        findPlayerWithIOPlayerId(opponent.getId()).ifPresent(opponentWithIo -> {
            GameEvent closeEvent = game.toBuilder().setType(EventType.OTHER_PLAYER_LEFT).build();
            opponentWithIo.getEventStream().onNext(closeEvent);
        });
        games.remove(game.getGameId());
    }

    public void onGameCommand(GameCommand command) {
        String playerId = Contexts.PLAYER_ID.get();
        Optional<PlayerWithIO> playerWithIO = findPlayerWithIOPlayerId(playerId);
        switch (command.getCommandCase()) {
            case START_GAME:
                startGameBetweenTwoPlayers(playerId, playerWithIO, command.getStartGame());
                break;
            case BOARD_MOVE:
                processPlayerMove(playerId, playerWithIO, command.getBoardMove());
                break;
            default:
                log("Unknown command " + command);
        }
    }

    private void startGameBetweenTwoPlayers(String playerId, Optional<PlayerWithIO> playerWithIO, StartGame startGameCommand) {
        Optional<PlayerWithIO> fromPlayer = findPlayerWithIOPlayerId(startGameCommand.getFromPlayer().getId());
        Optional<PlayerWithIO> toPlayer = findPlayerWithIOPlayerId(startGameCommand.getToPlayer().getId());

        if (fromPlayer.isPresent() && toPlayer.isPresent()) {
            GameEvent event = createNewGame(startGameCommand);
            games.put(event.getGameId(), event);

            log("Started game " + event.getGameId() + " between " + fromPlayer.get().getPlayer().getName() + " and " + toPlayer.get().getPlayer().getName());

            fromPlayer.get().getEventStream().onNext(event);
            toPlayer.get().getEventStream().onNext(event);
        } else {
            log("Could not find to or from player streams for which a start game was requested from:" + fromPlayer + ", to:" + toPlayer);
        }
    }

    private void processPlayerMove(String playerId, Optional<PlayerWithIO> playerMakingMove, BoardMove boardMove) {
        GameEvent gameEvent = games.get(boardMove.getGameId());

        if (gameEvent == null) {
            log("Requested board move for non-existing game " + boardMove.getGameId());
        } else if (playerMakingMove.isEmpty()) {
            log("Did not find player making the move, player-id: " + playerId);
        } else if (!eq(gameEvent.getNextPlayer(), playerMakingMove.get().getPlayer())) {
            log("Player " + playerMakingMove.get().getPlayer().getName() + " going before his turn on game " + gameEvent.getGameId());
        } else {
            GameEvent newEvent = gameEvent.toBuilder()
                .setType(EventType.BOARD_MOVE)
                .setNextPlayer(determineNextPlayer(gameEvent))
                .setBoard(updateBoardWithMove(playerMakingMove.get().getPlayer(), gameEvent, boardMove))
                .build();

            games.put(boardMove.getGameId(), newEvent);

            log("Move recorded on " + boardMove.getGameId() + " by " + playerMakingMove.get().getPlayer().getName());

            findPlayerWithIOPlayerId(gameEvent.getPlayerO().getId()).ifPresent(it -> it.getEventStream().onNext(newEvent));
            findPlayerWithIOPlayerId(gameEvent.getPlayerX().getId()).ifPresent(it -> it.getEventStream().onNext(newEvent));
        }
    }

    private Player determineNextPlayer(GameEvent gameEvent) {
        if (eq(gameEvent.getPlayerO(), gameEvent.getNextPlayer())) {
            return gameEvent.getPlayerX();
        } else {
            return gameEvent.getPlayerO();
        }
    }

    private Board updateBoardWithMove(Player player, GameEvent currentGameState, BoardMove boardMove) {
        String sign = eq(player, currentGameState.getPlayerO()) ? "O" : "X";
//    BoardRow updatedRow = currentGameState.getBoard().getRows(boardMove.getRow()).toBuilder()
//        .setColumns(boardMove.getColumn(), sign)
//        .build();
//    return currentGameState.getBoard().toBuilder()
//        .setRows(boardMove.getRow(), updatedRow)
//        .build();

        Board.Builder builder = currentGameState.getBoard().toBuilder();
        builder.getRowsBuilder(boardMove.getRow()).setColumns(boardMove.getColumn(), sign);
        return builder.build();
    }


    private boolean eq(Player p1, Player p2) {
        return p1.getId().equals(p2.getId());
    }

    private GameEvent createNewGame(StartGame startGameCommand) {
        return GameEvent.newBuilder()
            .setGameId(String.valueOf(gameIdSequence.getAndIncrement()))
            .setType(EventType.START_GAME)
            .setNextPlayer(startGameCommand.getFromPlayer())
            .setPlayerO(startGameCommand.getFromPlayer())
            .setPlayerX(startGameCommand.getToPlayer())
            .setBoard(createEmptyBoard())
            .build();
    }

    private Optional<PlayerWithIO> findPlayerWithIOPlayerId(String playerId) {
        return joinedPlayers.stream()
            .filter(it -> it.getPlayer() != null)
            .filter(it -> playerId.equals(it.getPlayer().getId()))
            .findFirst();
    }

    private Board createEmptyBoard() {
        return Board.newBuilder()
            .addRows(createEmptyRow())
            .addRows(createEmptyRow())
            .addRows(createEmptyRow())
            .build();
    }

    private BoardRow createEmptyRow() {
        return BoardRow.newBuilder().addColumns("").addColumns("").addColumns("").build();
    }

    private void log(String msg) {
        System.out.println(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString() + ": " + msg);
    }
}

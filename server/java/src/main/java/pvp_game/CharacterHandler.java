package pvp_game;

import io.gamemachine.core.Commands;
import io.gamemachine.core.GameGrid;
import io.gamemachine.core.GameMessageActor;
import io.gamemachine.core.Grid;
import io.gamemachine.core.PlayerCommands;
import io.gamemachine.core.PlayerService;
import io.gamemachine.messages.Character;
import io.gamemachine.messages.Characters;
import io.gamemachine.messages.GameMessage;
import io.gamemachine.messages.PvpGameMessage;
import io.gamemachine.messages.TrackData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

public class CharacterHandler extends GameMessageActor {

	public static String name = CharacterHandler.class.getSimpleName();
	LoggingAdapter logger = Logging.getLogger(getContext().system(), this);

	public static int defaultHealth = 1000;
	public static int defaultStamina = 1000;
	public static int defaultMagic = 1000;
	public static ConcurrentHashMap<String, Character> characters = new ConcurrentHashMap<String, Character>();

	@Override
	public void awake() {
		Commands.clientManagerRegister(name);
	}

	@Override
	public void onGameMessage(GameMessage gameMessage) {
		logger.debug("CharacterHandler command=" + gameMessage.pvpGameMessage.command);
		PvpGameMessage pvpGameMessage = gameMessage.pvpGameMessage;

		if (exactlyOnce(gameMessage)) {
			if (pvpGameMessage.command == 1) {
				Character character = getCharacter(pvpGameMessage.character.id);
				if (character != null) {
					PlayerService.getInstance().setCharacter(playerId, pvpGameMessage.character.id);
					logger.debug("Character " + pvpGameMessage.character.id + "set as current for " + playerId);
				}
				GameMessage msg = new GameMessage();
				msg.pvpGameMessage = pvpGameMessage;
				setReply(msg);
			} else if (pvpGameMessage.command == 3) {

			} else if (pvpGameMessage.command == 4) {
				pvpGameMessage.character = getCharacter(pvpGameMessage.character.id);
				GameMessage msg = new GameMessage();
				msg.pvpGameMessage = pvpGameMessage;
				setReply(msg);
			}
		} else {
			
			/*
			 * Large games probably want to store last location in the object store instead of just attaching it to the character directly.
			 * With more then a few hundred online players this will start to hit the db more then you really want.
			 */
			if (pvpGameMessage.command == 2) {
				logger.debug("Character request for " + pvpGameMessage.character.id + "/"
						+ pvpGameMessage.character.playerId);
				sendCharacter(pvpGameMessage.character);
			} else if (pvpGameMessage.command == 10) {
				for (Grid grid : GameGrid.gridsStartingWith("default")) {
					for (TrackData trackData : grid.getAll()) {
						if (trackData.entityType != TrackData.EntityType.PLAYER) {
							continue;
						}
						Character character = currentCharacter(trackData.id);
						if (character != null) {
							character.worldx = trackData.x;
							character.worldy = trackData.y;
							character.worldz = trackData.z;
							character.zone = trackData.zone;
							saveCharacter(character);
							logger.debug("Update character world position " +character.id);
						}
					}
				}

				tick(5l, saveMessage());
			}
		}
	}

	public static void createCharacter(String playerId, String id, String umaData) {
		Character character = getCharacter(id);
		if (character == null) {
			character = new Character();
			character.setId(id);
			character.setPlayerId(playerId);
			character.setHealth(defaultHealth);
			character.setStamina(defaultStamina);
			character.setMagic(defaultMagic);
			saveCharacter(character);
		}
		character.setUmaData(umaData);
		saveCharacter(character);
	}

	public static byte[] getCharacters(String playerId) {
		GameMessage gameMessage = new GameMessage();
		gameMessage.pvpGameMessage = new PvpGameMessage();
		gameMessage.pvpGameMessage.characters = new Characters();
		gameMessage.pvpGameMessage.characters.setCharactersList(Character.db().where("character_player_id = ?",
				playerId));
		return gameMessage.toByteArray();
	}

	
	private void sendCharacter(Character requestedCharacter) {
		Character character = getCharacter(requestedCharacter.id);
		if (character == null) {
			logger.warning("Unable to get character "+requestedCharacter.playerId+" "+requestedCharacter.id);
			return;
		}
		
		if (!requestedCharacter.hasIncludeUmaData()) {
			sendComplete(character.clone());
			return;
		}
		
		if (character.umaData == null) {
			logger.warning("umaData null for "+requestedCharacter.playerId+" "+requestedCharacter.id);
			return;
		}
		Iterable<String> iter = Splitter.fixedLength(1000).split(character.umaData);

		List<String> parts = Lists.newArrayList(iter.iterator());
		int x = 0;
		for (String part : parts) {
			sendPart(character.clone(),part,x,parts.size());
			logger.debug("sent part " + x + " for " + character.playerId);
			x++;
		}
	}

	private void sendComplete(Character character) {
		PvpGameMessage pvpGameMessage = new PvpGameMessage();
		pvpGameMessage.command = 2;
		pvpGameMessage.character = character;
		pvpGameMessage.character.umaData = null;
		pvpGameMessage.character.includeUmaData = false;
		GameMessage msg = new GameMessage();
		msg.pvpGameMessage = pvpGameMessage;
		PlayerCommands.sendGameMessage(msg, playerId);
	}
	
	private void sendPart(Character character, String part, int idx, int total) {
		PvpGameMessage pvpGameMessage = new PvpGameMessage();
		pvpGameMessage.command = 2;
		pvpGameMessage.character = character;
		pvpGameMessage.character.umaData = part;
		pvpGameMessage.character.part = idx;
		pvpGameMessage.character.parts = total;
		GameMessage msg = new GameMessage();
		msg.pvpGameMessage = pvpGameMessage;
		PlayerCommands.sendGameMessage(msg, playerId);
	}
	
	public static int currentHealth(String playerId) {
		return currentCharacter(playerId).health;
	}
	
	public static int currentStamina(String playerId) {
		return currentCharacter(playerId).stamina;
	}
	
	public static int currentMagic(String playerId) {
		return currentCharacter(playerId).magic;
	}
	
	public static Character currentCharacter(String playerId) {
		String id = PlayerService.getInstance().getCharacter(playerId);
		if (id == null) {
			throw new RuntimeException("No character id for player " + playerId);
		}
		return getCharacter(id);
	}

	public static Character getCharacter(String id) {
		if (characters.containsKey(id)) {
			return characters.get(id);
		} else {
			Character character = Character.db().findFirst("character_id = ?", id);
			if (character != null) {
				characters.put(id, character);
				return character;
			} else {
				return null;
			}
		}
	}
	
	public static String PlayerIdFromCharacterId(String characterId) {
		Character character = Character.db().findFirst("character_id = ?",characterId);
		if (character == null) {
			return null;
		} else {
			return character.playerId;
		}
	}
	
	
	public static Character cloneCharacter(String playerId, String characterId, String clonedPlayerId, String clonedId) {
		Character base = Character.db().findFirst("character_player_id = ? and character_id = ?", playerId,
				characterId);
		if (base == null) {
			return null;
		}
		
		Character existing = Character.db().findFirst("character_player_id = ? and character_id = ?", clonedPlayerId,
				clonedId);
		
		if (existing == null) {
			Character cloned = base.clone();
			cloned.setId(clonedId);
			cloned.setPlayerId(clonedPlayerId);
			cloned.setRecordId(null);
			saveCharacter(cloned);
			return cloned;
		} else {
			return existing;
		}
	}
	
	private static void saveCharacter(Character character) {
		if (!Character.db().save(character)) {
			throw new RuntimeException("Error saving Character " + character.id);
		}
	}

	@Override
	public void onPlayerDisconnect(String playerId) {

	}

	private GameMessage saveMessage() {
		GameMessage msg = new GameMessage();
		PvpGameMessage pvpGameMessage = new PvpGameMessage();
		pvpGameMessage.command = 10;
		msg.pvpGameMessage = pvpGameMessage;
		return msg;
	}

	@Override
	public void preStart() {
		tick(5l, saveMessage());
	}

	public void tick(long delay, GameMessage message) {
		getContext()
				.system()
				.scheduler()
				.scheduleOnce(Duration.create(delay, TimeUnit.SECONDS), getSelf(), message, getContext().dispatcher(),
						null);
	}

	@Override
	public void onTick(String message) {
		// TODO Auto-generated method stub
		
	}

}

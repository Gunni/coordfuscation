/**
 * Copyright (c) 2013 Gabríel Arthúr Pétursson, Gunnar Guðvarðarson
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package cc.kjarni.aetherous.coordfuscation;

import java.util.HashMap;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.packetwrapper.*;
import com.comphenix.protocol.Packets;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.GamePhase;

public final class Coordfuscation extends JavaPlugin
{
	/**
	 * Minecraft has various issues with large coordinates.
	 * There are issues for item frames (MC-3493).
	 * There are huge floating point issues with coords > 20M.
	 * 
	 * Also if the absolute number of X or Z is set greater than
	 * 3.2*10^7 the client will be kicked for "Illegal position"
	 */
	private static int MIN_OFFSET = -80000;
	private static int MAX_OFFSET = 80000;

	private ProtocolManager protocolManager;
	private final HashMap<Player, Offset> offsets = new HashMap<Player, Offset>();

	private Offset offsetCoords(Player player)
	{
		Offset offset = offsets.get(player);

		if (offset == null)
		{
			// Generate random offset for this player
			int offX = MIN_OFFSET + (int)(Math.random() * ((MAX_OFFSET - MIN_OFFSET) + 1));
			int offY = MIN_OFFSET + (int)(Math.random() * ((MAX_OFFSET - MIN_OFFSET) + 1));

			// Align to chunks
			offX &= ~0x0F;
			offY &= ~0x0F;

			offsets.put(player, new Offset(offX, offY));
		}

		return offset;
	}

	/**
	 * Get the offset for a given Player
	 * 
	 * @param Player
	 * @return Offset
	 */
	public Offset getOffsetfor(Player player)
	{
		return offsetCoords(player);
	}

	public void onEnable()
	{
		// Plugin can NOT be enabled on a live server unless it's empty
		// Reason: the moment the first packet that got sent here would get a new
		// random offset added to it and all packets following that would too.

		// Doing so would cause undefined behavior...

		getLogger().warning("coordfuscation plugin is being enabled, all players are being kicked and must reconnect!");
		kickAll();

		protocolManager = ProtocolLibrary.getProtocolManager();

		protocolManager.addPacketListener(new PacketAdapter(this, ConnectionSide.CLIENT_SIDE, ListenerPriority.HIGHEST, GamePhase.BOTH,
				Packets.Client.PLAYER_POSITION,
				Packets.Client.PLAYER_LOOK_MOVE,
				Packets.Client.BLOCK_DIG,
				Packets.Client.PLACE,
				Packets.Client.UPDATE_SIGN)
		{
			@Override
			public void onPacketReceiving(PacketEvent event)
			{
				PacketContainer packet = event.getPacket();
				Offset off = offsetCoords(event.getPlayer());

				switch (event.getPacketID())
				{
					case Packets.Client.PLAYER_POSITION: { // Player Position 0x0B
						Packet0BPlayerPosition p = new Packet0BPlayerPosition(packet);
						p.setX(p.getX() - off.X);
						p.setZ(p.getZ() - off.Y);
						break;
					}
					case Packets.Client.PLAYER_LOOK_MOVE: { // Player Position and Look 0x0D
						Packet0DPlayerPositionAndLook p = new Packet0DPlayerPositionAndLook(packet);
						
						if (p.getY() == -999.0D && p.getStance() == -999.0D)
							break;
						
						p.setX(p.getX() - off.X);
						p.setZ(p.getZ() - off.Y);
						
						if (off.teleported)
						{
							double dx = p.getX() - off.lastTeleportX;
							double dz = p.getZ() - off.lastTeleportZ;
							
							if (Math.abs(dx) + Math.abs(dz) <= 1/1024)
							{
								p.setX(off.lastTeleportX);
								p.setZ(off.lastTeleportZ);
								
								off.teleported = false;
							}
						}
						break;
					}
					case Packets.Client.BLOCK_DIG: { // Player Digging 0x0E
						Packet0EPlayerDigging p = new Packet0EPlayerDigging(packet);
						p.setX(p.getX() - off.X);
						p.setZ(p.getZ() - off.Y);
						break;
					}
					case Packets.Client.PLACE: { // Player Block Placement 0x0F
						Packet0FPlayerBlockPlacement p = new Packet0FPlayerBlockPlacement(packet);
						
						if (p.getX() == -1 && p.getY() == 255 && p.getZ() == -1 && p.getDirection() == 255)
							break;
						
						p.setX(p.getX() - off.X);
						p.setZ(p.getZ() - off.Y);
						break;
					}
					case Packets.Client.UPDATE_SIGN: { // Update Sign 0x82
						Packet82UpdateSign p = new Packet82UpdateSign(packet);
						p.setX(p.getX() - off.X);
						p.setZ(p.getZ() - off.Y);
						break;
					}
				}
				
				event.setPacket(packet);
			}
		});

		// Here we ADD the offset to everything being sent TO the player.
		protocolManager.addPacketListener(new PacketAdapter(this, ConnectionSide.SERVER_SIDE, ListenerPriority.HIGHEST, GamePhase.BOTH,
				Packets.Server.SPAWN_POSITION,
				Packets.Server.PLAYER_LOOK_MOVE,
				Packets.Server.ENTITY_LOCATION_ACTION,
				Packets.Server.NAMED_ENTITY_SPAWN,
				Packets.Server.VEHICLE_SPAWN,
				Packets.Server.MOB_SPAWN,
				Packets.Server.ENTITY_PAINTING,
				Packets.Server.ADD_EXP_ORB,
				Packets.Server.ENTITY_TELEPORT,
				Packets.Server.MAP_CHUNK,
				Packets.Server.MULTI_BLOCK_CHANGE,
				Packets.Server.BLOCK_CHANGE,
				Packets.Server.PLAY_NOTE_BLOCK,
				Packets.Server.BLOCK_BREAK_ANIMATION,
				Packets.Server.MAP_CHUNK_BULK,
				Packets.Server.EXPLOSION,
				Packets.Server.WORLD_EVENT,
				Packets.Server.NAMED_SOUND_EFFECT,
				0x3F, // Particle
				Packets.Server.WEATHER,
				Packets.Server.UPDATE_SIGN,
				Packets.Server.TILE_ENTITY_DATA,
				Packets.Server.OPEN_TILE_ENTITY
				) {
			@Override
			public void onPacketSending(PacketEvent event)
			{
				PacketContainer packet = event.getPacket().deepClone();
				Offset off = offsetCoords(event.getPlayer());

				switch (event.getPacketID())
				{
					case Packets.Server.SPAWN_POSITION: { // Spawn Position 0x06
						Packet06SpawnPosition p = new Packet06SpawnPosition(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.PLAYER_LOOK_MOVE: { // Player Position and Look 0x0D
						Packet0DPlayerPositionAndLook p = new Packet0DPlayerPositionAndLook(packet);

						off.teleported = true;
						off.lastTeleportX = p.getX();
						off.lastTeleportZ = p.getZ();

						getLogger().info("Moving to " + p.getX() + ", " + p.getZ());
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.ENTITY_LOCATION_ACTION: { // Use Bed 0x11
						Packet11UseBed p = new Packet11UseBed(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.NAMED_ENTITY_SPAWN: { // Spawn Named Entity 0x14
						Packet14SpawnNamedEntity p = new Packet14SpawnNamedEntity(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.VEHICLE_SPAWN: { // Spawn Object/Vehicle 0x17
						Packet17SpawnObjectVehicle p = new Packet17SpawnObjectVehicle(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.MOB_SPAWN: { // Spawn Mob 0x18
						Packet18SpawnMob p = new Packet18SpawnMob(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.ENTITY_PAINTING: { // Spawn Painting 0x19
						Packet19SpawnPainting p = new Packet19SpawnPainting(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.ADD_EXP_ORB: { // Spawn Experience Orb 0x1A
						Packet1ASpawnExperienceOrb p = new Packet1ASpawnExperienceOrb(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.ENTITY_TELEPORT: { // Entity Teleport 0x22
						Packet22EntityTeleport p = new Packet22EntityTeleport(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.MAP_CHUNK: { // Chunk Data 0x33
						Packet33ChunkData p = new Packet33ChunkData(packet);
						p.setChunkX(p.getChunkX() + off.X / 16);
						p.setChunkZ(p.getChunkZ() + off.Y / 16);
						break;
					}
					case Packets.Server.MULTI_BLOCK_CHANGE: { // Multi Block Change 0x34
						Packet34MultiBlockChange p = new Packet34MultiBlockChange(packet);
						p.setOriginChunkX(p.getOriginChunkX() + off.X / 16);
						p.setOriginChunkZ(p.getOriginChunkZ() + off.Y / 16);
						break;
					}
					case Packets.Server.BLOCK_CHANGE: { // Block Change 0x35
						Packet35BlockChange p = new Packet35BlockChange(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.PLAY_NOTE_BLOCK: { // Block Action 0x36
						Packet36BlockAction p = new Packet36BlockAction(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.BLOCK_BREAK_ANIMATION: { // Block Break Animation 0x37
						Packet37BlockBreakAnimation p = new Packet37BlockBreakAnimation(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.MAP_CHUNK_BULK: { // Map Chunk Bulk 0x38
						int[] xCoords = packet.getIntegerArrays().read(0);
						int[] zCoords = packet.getIntegerArrays().read(1);

						for (int i = 0; i < xCoords.length; i++)
						{
							xCoords[i] += off.X / 16;
							zCoords[i] += off.Y / 16;
						}

						packet.getIntegerArrays()
							.write(0, xCoords)
							.write(1, zCoords);
						break;
					}
					case Packets.Server.EXPLOSION: { // Explosion 0x3C
						Packet3CExplosion p = new Packet3CExplosion(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.WORLD_EVENT: { // Sound or Particle Effect 0x3D
						Packet3DSoundOrParticleEffect p = new Packet3DSoundOrParticleEffect(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.NAMED_SOUND_EFFECT: { // Named Sound Effect 0x3E
						Packet3ENamedSoundEffect p = new Packet3ENamedSoundEffect(packet);
						p.setEffectPositionX(p.getEffectPositionX() + off.X);
						p.setEffectPositionZ(p.getEffectPositionZ() + off.Y);
						break;
					}
					case 0x3F: { // Particle 0x3F
						packet.getFloat()
							.write(0, packet.getFloat().read(0) + off.X)
							.write(2, packet.getFloat().read(2) + off.Y);
						break;
					}
					case Packets.Server.WEATHER: { // Spawn Global Entity 0x47
						Packet47SpawnGlobalEntity p = new Packet47SpawnGlobalEntity(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.UPDATE_SIGN: { // Update Sign 0x82
						Packet82UpdateSign p = new Packet82UpdateSign(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.TILE_ENTITY_DATA: { // Update Tile Entity 0x84
						Packet84UpdateTileEntity p = new Packet84UpdateTileEntity(packet);
						p.setX(p.getX() + off.X);
						p.setZ(p.getZ() + off.Y);
						break;
					}
					case Packets.Server.OPEN_TILE_ENTITY: { // Tile Editor Open? 0x85
						packet.getIntegers()
							.write(1, packet.getIntegers().read(1) + off.X)
							.write(3, packet.getIntegers().read(3) + off.Y);
						// TODO missing in packetwrapper
						break;
					}
				}
				
				event.setPacket(packet);
			}
		});
	}

	public void onDisable()
	{
		// Plugin can not be disabled on a running server
		// because it is doing translations of coordinates
		// payers with offset coordinates.
		
		// Doing so would cause undefined behavior...

		getLogger().warning("coordfuscation plugin is being disabled, all players are being kicked and must reconnect!");
		kickAll();
	}
	
	public void kickAll()
	{
		for (Player player : getServer().getOnlinePlayers())
		{
			player.kickPlayer("coordfuscation plugin is being turned on or off, rejoining is required!");
		}
	}

	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args)
	{
		if(cmd.getName().equalsIgnoreCase("/getoffsets"))
		{
			if(sender.hasPermission("coordfuscation.getoffset"))
			{
				sender.sendMessage("getcoordfuscationoffsets command invoked!");
			}

			return true;
		}

		return false;
	}
}

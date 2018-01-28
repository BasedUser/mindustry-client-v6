package io.anuke.mindustry.core;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.IntSet;
import com.badlogic.gdx.utils.TimeUtils;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.entities.Bullet;
import io.anuke.mindustry.entities.BulletType;
import io.anuke.mindustry.entities.Player;
import io.anuke.mindustry.entities.SyncEntity;
import io.anuke.mindustry.entities.enemies.Enemy;
import io.anuke.mindustry.entities.enemies.EnemyType;
import io.anuke.mindustry.graphics.Fx;
import io.anuke.mindustry.io.Platform;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.Net.SendMode;
import io.anuke.mindustry.net.NetworkIO;
import io.anuke.mindustry.net.Packets.*;
import io.anuke.mindustry.resource.Upgrade;
import io.anuke.mindustry.resource.Weapon;
import io.anuke.mindustry.world.Tile;
import io.anuke.ucore.UCore;
import io.anuke.ucore.core.Effects;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.entities.BaseBulletType;
import io.anuke.ucore.entities.Entities;
import io.anuke.ucore.entities.Entity;
import io.anuke.ucore.entities.EntityGroup;
import io.anuke.ucore.modules.Module;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static io.anuke.mindustry.Vars.*;

public class NetClient extends Module {
    public static final Color[] colorArray = {Color.ORANGE, Color.SCARLET, Color.LIME, Color.PURPLE,
            Color.GOLD, Color.PINK, Color.SKY, Color.GOLD, Color.VIOLET,
            Color.GREEN, Color.CORAL, Color.CYAN, Color.CHARTREUSE};
    boolean connecting = false;
    boolean gotData = false;
    boolean kicked = false;
    IntSet recieved = new IntSet();
    IntSet dead = new IntSet();
    float playerSyncTime = 2;
    float dataTimeout = 60*18; //18 seconds timeout

    public NetClient(){

        Net.handleClient(Connect.class, packet -> {
            Net.setClientLoaded(false);
            recieved.clear();
            dead.clear();
            connecting = true;
            gotData = false;
            kicked = false;

            ui.loadfrag.hide();
            ui.loadfrag.show("$text.connecting.data");

            Entities.clear();

            ConnectPacket c = new ConnectPacket();
            c.name = player.name;
            c.android = android;
            Net.send(c, SendMode.tcp);

            Timers.runTask(dataTimeout, () -> {
                if (!gotData) {
                    Gdx.app.error("Mindustry", "Failed to load data!");
                    ui.loadfrag.hide();
                    Net.disconnect();
                }
            });
        });

        Net.handleClient(Disconnect.class, packet -> {
            if (kicked) return;

            Timers.runFor(3f, ui.loadfrag::hide);

            state.set(State.menu);

            ui.showError("$text.disconnect");
            connecting = false;

            Platform.instance.updateRPC();
        });

        Net.handleClient(WorldData.class, data -> {
            UCore.log("Recieved world data: " + data.stream.available() + " bytes.");
            NetworkIO.loadWorld(data.stream);
            player.set(world.getSpawnX(), world.getSpawnY());

            gotData = true;

            finishConnecting();
        });

        Net.handleClient(CustomMapPacket.class, packet -> {
            UCore.log("Recieved custom map: " + packet.stream.available() + " bytes.");

            //custom map is always sent before world data
            Pixmap pixmap = NetworkIO.loadMap(packet.stream);

            world.maps().setNetworkMap(pixmap);

            MapAckPacket ack = new MapAckPacket();
            Net.send(ack, SendMode.tcp);
        });

        Net.handleClient(SyncPacket.class, packet -> {
            if (!gotData) return;

            ByteBuffer data = ByteBuffer.wrap(packet.data);

            byte groupid = data.get();

            EntityGroup<?> group = Entities.getGroup(groupid);

            while (data.position() < data.capacity()) {
                int id = data.getInt();

                SyncEntity entity = (SyncEntity) group.getByID(id);

                if (entity == null || id == player.id) {
                    if (id != player.id) {
                        UCore.log("Requesting entity " + id, "group " + group.getType());
                        EntityRequestPacket req = new EntityRequestPacket();
                        req.id = id;
                        Net.send(req, SendMode.udp);
                    }
                    data.position(data.position() + SyncEntity.getWriteSize((Class<? extends SyncEntity>) group.getType()));
                } else {
                    entity.read(data);
                }
            }
        });

        Net.handleClient(StateSyncPacket.class, packet -> {

            System.arraycopy(packet.items, 0, state.inventory.getItems(), 0, packet.items.length);

            state.enemies = packet.enemies;
            state.wavetime = packet.countdown;
            state.wave = packet.wave;

            Timers.resetTime(packet.time + (float) (TimeUtils.timeSinceMillis(packet.timestamp) / 1000.0 * 60.0));

            ui.hudfrag.updateItems();
        });

        Net.handleClient(EnemySpawnPacket.class, spawn -> {
            //duplicates.
            if (enemyGroup.getByID(spawn.id) != null ||
                    recieved.contains(spawn.id) || dead.contains(spawn.id)) return;

            recieved.add(spawn.id);

            Enemy enemy = new Enemy(EnemyType.getByID(spawn.type));
            enemy.set(spawn.x, spawn.y);
            enemy.tier = spawn.tier;
            enemy.lane = spawn.lane;
            enemy.id = spawn.id;
            enemy.health = spawn.health;
            enemy.add();

            Effects.effect(Fx.spawn, enemy);
        });

        Net.handleClient(EnemyDeathPacket.class, spawn -> {
            Enemy enemy = enemyGroup.getByID(spawn.id);
            if (enemy != null) enemy.onDeath();
            dead.add(spawn.id);
        });

        Net.handleClient(BulletPacket.class, packet -> {
            //TODO shoot effects for enemies, clientside as well as serverside
            BulletType type = (BulletType) BaseBulletType.getByID(packet.type);
            Entity owner = enemyGroup.getByID(packet.owner);
            new Bullet(type, owner, packet.x, packet.y, packet.angle).add();
        });

        Net.handleClient(BlockDestroyPacket.class, packet -> {
            Tile tile = world.tile(packet.position % world.width(), packet.position / world.width());
            if (tile != null && tile.entity != null) {
                tile.entity.onDeath(true);
            }
        });

        Net.handleClient(BlockUpdatePacket.class, packet -> {
            Tile tile = world.tile(packet.position % world.width(), packet.position / world.width());
            if (tile != null && tile.entity != null) {
                tile.entity.health = packet.health;
            }
        });

        Net.handleClient(BlockSyncPacket.class, packet -> {
            if (!gotData) return;

            DataInputStream stream = new DataInputStream(packet.stream);

            try {

                float time = stream.readFloat();
                float elapsed = Timers.time() - time;

                while (stream.available() > 0) {
                    int pos = stream.readInt();

                    //TODO what if there's no entity? new code
                    Tile tile = world.tile(pos % world.width(), pos / world.width());

                    byte times = stream.readByte();

                    for (int i = 0; i < times; i++) {
                        tile.entity.timer.getTimes()[i] = stream.readFloat();
                    }

                    short data = stream.readShort();
                    tile.setPackedData(data);

                    tile.entity.readNetwork(stream, elapsed);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                UCore.error(e);
                //do nothing else...
                //TODO fix
            }

        });

        Net.handleClient(DisconnectPacket.class, packet -> {
            Player player = playerGroup.getByID(packet.playerid);

            if (player != null) {
                player.remove();
            }

            Platform.instance.updateRPC();
        });

        Net.handleClient(PlayerSpawnPacket.class, packet -> {
            //duplicates.
            if (enemyGroup.getByID(packet.id) != null ||
                    recieved.contains(packet.id)) return;
            recieved.add(packet.id);

            Player player = new Player();
            player.x = packet.x;
            player.y = packet.y;
            player.isAndroid = packet.android;
            player.name = packet.name;
            player.id = packet.id;
            player.weaponLeft = (Weapon) Upgrade.getByID(packet.weaponleft);
            player.weaponRight = (Weapon) Upgrade.getByID(packet.weaponright);

            player.interpolator.last.set(player.x, player.y);
            player.interpolator.target.set(player.x, player.y);
            player.add();

            Platform.instance.updateRPC();
        });

        Net.handleClient(KickPacket.class, packet -> {
            kicked = true;
            Net.disconnect();
            state.set(State.menu);
            ui.showError("$text.server.kicked." + packet.reason.name());
            ui.loadfrag.hide();
        });

        Net.handleClient(GameOverPacket.class, packet -> {
            kicked = true;
            ui.restart.show();
        });

        Net.handleClient(FriendlyFireChangePacket.class, packet -> state.friendlyFire = packet.enabled);
    }

    @Override
    public void update(){
        if(!Net.client()) return;

        if(!state.is(State.menu) && Net.active()){
            if(gotData) sync();
        }else if(!connecting){
            Net.disconnect();
        }
    }

    private void finishConnecting(){
        Net.send(new ConnectConfirmPacket(), SendMode.tcp);
        state.set(State.playing);
        Net.setClientLoaded(true);
        connecting = false;
        ui.loadfrag.hide();
        ui.join.hide();
    }

    public void beginConnecting(){
        connecting = true;
    }

    public void disconnectQuietly(){
        kicked = true;
        Net.disconnect();
    }

    public String colorizeName(int id, String name){
        return name == null ? null : "[#" + colorArray[id % colorArray.length].toString().toUpperCase() + "]" + name;
    }

    void sync(){
        if(Timers.get("syncPlayer", playerSyncTime)){
            byte[] bytes = new byte[player.getWriteSize()];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            player.write(buffer);

            PositionPacket packet = new PositionPacket();
            packet.data = bytes;
            Net.send(packet, SendMode.udp);
        }

        if(Timers.get("updatePing", 60)){
            Net.updatePing();
        }
    }
}

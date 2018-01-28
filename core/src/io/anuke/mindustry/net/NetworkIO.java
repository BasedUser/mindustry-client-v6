package io.anuke.mindustry.net;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.TimeUtils;
import io.anuke.mindustry.game.GameMode;
import io.anuke.mindustry.resource.Upgrade;
import io.anuke.mindustry.resource.Weapon;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.ColorMapper;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.WorldGenerator;
import io.anuke.mindustry.world.blocks.Blocks;
import io.anuke.mindustry.world.blocks.types.BlockPart;
import io.anuke.mindustry.world.blocks.types.Rock;
import io.anuke.ucore.core.Timers;
import io.anuke.ucore.entities.Entities;

import java.io.*;

import static io.anuke.mindustry.Vars.*;

public class NetworkIO {

    public static void writeMap(Pixmap map, OutputStream os){
        try(DataOutputStream stream = new DataOutputStream(os)){
            stream.writeShort(map.getWidth());
            stream.writeShort(map.getHeight());

            int width = map.getWidth();
            int cap = map.getWidth() * map.getHeight();
            int pos = 0;

            while(pos < cap){
                int color = map.getPixel(pos % width, pos / width);
                byte id = ColorMapper.getColorID(color);

                int length = 1;
                while(true){
                    if(pos >= cap || length > 254){
                        break;
                    }

                    pos ++;

                    int next = map.getPixel(pos % width, pos / width);
                    if(next != color){
                        pos --;
                        break;
                    }else{
                        length ++;
                    }
                }

                if(id == -1) id = 0;
                stream.writeByte((byte)(length > 127 ? length - 256 : length));
                stream.writeByte(id);

                pos ++;
            }
        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    public static Pixmap loadMap(InputStream is){
        try(DataInputStream stream = new DataInputStream(is)){
            short width = stream.readShort();
            short height = stream.readShort();
            Pixmap pixmap = new Pixmap(width, height, Format.RGBA8888);

            int pos = 0;
            while(stream.available() > 0){
                int length = stream.readByte();
                byte id = stream.readByte();
                if(length < 0) length += 256;
                int color = ColorMapper.getColorByID(id);

                for(int p = 0; p < length; p ++){
                    pixmap.drawPixel(pos % width, pos / width,color);
                    pos ++;
                }
            }


            return pixmap;
        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    public static void writeWorld(int playerID, ByteArray upgrades, OutputStream os){

        try(DataOutputStream stream = new DataOutputStream(os)){

            stream.writeFloat(Timers.time()); //timer time
            stream.writeLong(TimeUtils.millis()); //timestamp

            //--GENERAL STATE--
            stream.writeByte(state.mode.ordinal()); //gamemode
            stream.writeByte(world.getMap().custom ? -1 : world.getMap().id); //map ID

            stream.writeInt(state.wave); //wave
            stream.writeFloat(state.wavetime); //wave countdown
            stream.writeInt(state.enemies); //enemy amount

            stream.writeBoolean(state.friendlyFire); //friendly fire state
            stream.writeInt(playerID); //player remap ID

            //--INVENTORY--

            for(int i = 0; i < state.inventory.getItems().length; i ++){ //items
                stream.writeInt(state.inventory.getItems()[i]);
            }

            stream.writeByte(upgrades.size); //upgrade data

            for(int i = 0; i < upgrades.size; i ++){
                stream.writeByte(upgrades.get(i));
            }

            //--MAP DATA--

            //seed
            stream.writeInt(world.getSeed());

            int totalblocks = 0;
            int totalrocks = 0;

            for(int x = 0; x < world.width(); x ++){
                for(int y = 0; y < world.height(); y ++){
                    Tile tile = world.tile(x, y);

                    if(tile.breakable()){
                        if(tile.block() instanceof Rock){
                            totalrocks ++;
                        }else{
                            totalblocks ++;
                        }
                    }
                }
            }

            //amount of rocks
            stream.writeInt(totalrocks);

            //write all rocks
            for(int x = 0; x < world.width(); x ++) {
                for (int y = 0; y < world.height(); y++) {
                    Tile tile = world.tile(x, y);

                    if (tile.block() instanceof Rock) {
                        stream.writeInt(tile.packedPosition());
                    }
                }
            }

            //tile amount
            stream.writeInt(totalblocks);

            for(int x = 0; x < world.width(); x ++){
                for(int y = 0; y < world.height(); y ++){
                    Tile tile = world.tile(x, y);

                    if(tile.breakable() && !(tile.block() instanceof Rock)){

                        stream.writeInt(x + y*world.width()); //tile pos
                        //TODO will break if block number gets over BYTE_MAX
                        stream.writeByte(tile.block().id); //block ID

                        if(tile.block() instanceof BlockPart){
                            stream.writeByte(tile.link);
                        }

                        if(tile.entity != null){
                            stream.writeShort(tile.getPackedData());
                            stream.writeShort((short)tile.entity.health); //health

                            //items
                            for(int i = 0; i < tile.entity.items.length; i ++){
                                stream.writeInt(tile.entity.items[i]);
                            }

                            //timer data

                            //amount of active timers
                            byte times = 0;

                            for(; times < tile.entity.timer.getTimes().length; times ++){
                                if(tile.entity.timer.getTimes()[times] <= 1){
                                    break;
                                }
                            }

                            stream.writeByte(times);

                            for(int i = 0; i < times; i ++){
                                stream.writeFloat(tile.entity.timer.getTimes()[i]);
                            }

                            tile.entity.write(stream);
                        }
                    }
                }
            }

        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    /**Return whether a custom map is expected, and thus whether the client should wait for additional data.*/
    public static void loadWorld(InputStream is){

        try(DataInputStream stream = new DataInputStream(is)){
            float timerTime = stream.readFloat();
            long timestamp = stream.readLong();

            Timers.resetTime(timerTime + (TimeUtils.timeSinceMillis(timestamp) / 1000f) * 60f);

            //general state
            byte mode = stream.readByte();
            byte mapid = stream.readByte();

            int wave = stream.readInt();
            float wavetime = stream.readFloat();
            int enemies = stream.readInt();
            boolean friendlyfire = stream.readBoolean();

            state.enemies = enemies;
            state.wave = wave;
            state.wavetime = wavetime;
            state.mode = GameMode.values()[mode];
            state.friendlyFire = friendlyfire;

            int pid = stream.readInt();

            //inventory
            for(int i = 0; i < state.inventory.getItems().length; i ++){
                state.inventory.getItems()[i] = stream.readInt();
            }

            ui.hudfrag.updateItems();

            control.upgrades().getWeapons().clear();
            control.upgrades().getWeapons().add(Weapon.blaster);

            byte weapons = stream.readByte();

            for(int i = 0; i < weapons; i ++){
                control.upgrades().getWeapons().add((Weapon) Upgrade.getByID(stream.readByte()));
            }

            player.weaponLeft = player.weaponRight = control.upgrades().getWeapons().peek();
            ui.hudfrag.updateWeapons();

            Entities.clear();
            player.id = pid;
            player.add();

            //map

            int seed = stream.readInt();

            world.loadMap(world.maps().getMap(mapid), seed);
            renderer.clearTiles();

            player.set(world.getCore().worldx(), world.getCore().worldy());

            for(int x = 0; x < world.width(); x ++){
                for(int y = 0; y < world.height(); y ++){
                    Tile tile = world.tile(x, y);

                    //remove breakables like rocks
                    if(tile.breakable()){
                        world.tile(x, y).setBlock(Blocks.air);
                    }
                }
            }

            int rocks = stream.readInt();

            for(int i = 0; i < rocks; i ++){
                int pos = stream.readInt();
                Tile tile = world.tile(pos % world.width(), pos / world.width());
                Block result = WorldGenerator.rocks.get(tile.floor());
                if(result != null) tile.setBlock(result);
            }

            int tiles = stream.readInt();

            for(int i = 0; i < tiles; i ++){
                int pos = stream.readInt();
                byte blockid = stream.readByte();

                Tile tile = world.tile(pos % world.width(), pos / world.width());
                tile.setBlock(Block.getByID(blockid));

                if(tile.block() == Blocks.blockpart){
                    tile.link = stream.readByte();
                }

                if(tile.entity != null){
                    short data = stream.readShort();
                    short health = stream.readShort();

                    tile.entity.health = health;
                    tile.setPackedData(data);

                    for(int j = 0; j < tile.entity.items.length; j ++){
                        tile.entity.items[j] = stream.readInt();
                    }

                    byte timers = stream.readByte();
                    for(int time = 0; time < timers; time ++){
                        tile.entity.timer.getTimes()[time] = stream.readFloat();
                    }

                    tile.entity.read(stream);
                }
            }

        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }
}

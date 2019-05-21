package io.anuke.mindustry.world.blocks.distribution;

import io.anuke.annotations.Annotations.Struct;
import io.anuke.annotations.Annotations.StructField;
import io.anuke.arc.Core;
import io.anuke.arc.collection.IntArray;
import io.anuke.arc.function.Consumer;
import io.anuke.arc.graphics.g2d.Draw;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.math.Angles;
import io.anuke.arc.math.Mathf;
import io.anuke.arc.math.geom.Geometry;
import io.anuke.arc.math.geom.Point2;
import io.anuke.arc.util.Time;
import io.anuke.mindustry.gen.ItemPos;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.blocks.distribution.Conveyor.ConveyorEntity;

import static io.anuke.mindustry.Vars.*;

/**Stores one continuous line of conveyors with one input and one output.*/
public class ConveyorLine{
    /**distance units per conveyor block*/
    private static final int unitMult = 3000;
    /**spacing between items*/
    private static final int itemSpacing = unitMult / 3;
    /**edges of the conveyor, rotated*/
    private static final Point2[] edges = {new Point2(1, 1), new Point2(-1, 1), new Point2(-1, -1), new Point2(1, -1)};
    /**last frame drawn*/
    private long lastFrameID = -1;
    /**start and end tiles of this line*/
    private Tile start, end;
    /**items, sorted from back of conveyor to front of conveyor.*/
    private final IntArray items = new IntArray();
    /**seed that updates this line*/
    private Tile seed;
    /**movement speed of items in units*/
    private final int speed;

    private int index = 0;

    public ConveyorLine(Tile seed){
        //add seed entity so it updates
        this.seed = seed;
        this.seed.entity.add();
        this.speed = (int)(((Conveyor)seed.block()).speed * unitMult);
        this.seed.<ConveyorEntity>entity().line = this;
        this.start = this.end = seed;
    }

    public void handleItem(Tile tile, Item item){
        //distance in conveyor units from end
        int rawDst = (1 + Math.max(Math.abs(tile.x - end.x), Math.abs(tile.y - end.y))) * unitMult;
        int total = 0;
        int lastTotal = 0;
        for(int i = items.size - 1; i >= 0; i--){
            int curr = items.get(i);
            total += ItemPos.space(curr);
            if(rawDst < total){ //found place to insert

                int inserti = i + 1;
                int result = ItemPos.get((byte)item.id, rawDst - lastTotal);
                if(inserti == items.size){
                    items.add(result);
                }else{
                    items.insert(inserti, result);
                }
                //update previous item's distance
                items.set(i, ItemPos.get(ItemPos.item(curr), ItemPos.space(curr) - (rawDst - lastTotal)));
                return;
            }

            lastTotal = total;
        }

        int toInsert = ItemPos.get((byte)item.id, rawDst - total);
        if(items.size == 0){
            items.add(toInsert);
        }else{
            items.insert(0, toInsert);
        }
    }

    public void draw(){
        if(lastFrameID == Core.graphics.getFrameId()){
            return;
        }

        lastFrameID = Core.graphics.getFrameId();
        int offset = 0;
        Point2 pt = Geometry.d4[end.rotation()];
        int dx = -pt.x, dy = -pt.y;
        int length = length();
        float startX = end.worldx() + pt.x*tilesize/2f, startY = end.worldy() + pt.y*tilesize/2f;
        float itemSize = 5f;

        for(int i = items.size - 1; i >= 0; i --){
            int item = items.get(i);
            offset += ItemPos.space(item);
            TextureRegion region = content.item(ItemPos.item(item)).icon(Item.Icon.medium);
            float posOffset = offset / (float)unitMult * tilesize;
            float x = startX + dx*posOffset, y = startY + dy*posOffset;
            Tile on = get(length - 1 - offset / unitMult);
            ConveyorEntity ent = on.entity();
            if(ent.blendbits == 1){ //rotated
                Point2 p = edges[on.rotation()];
                int ex = p.x * ent.blendsclx, ey = p.y * ent.blendscly;
                float degrees = ((offset/(float)unitMult)%1f)*90f;
                x = on.worldx() + ex*tilesize/2f - ex*Angles.trnsx(degrees, tilesize/2f);
                y = on.worldy() + ey*tilesize/2f - ey*Angles.trnsy(degrees, tilesize/2f);
            }

            Draw.rect(region, x, y, itemSize, itemSize);
        }
    }

    /** adds a tile to the tail of this line.*/
    public void addLast(Tile tile){
        tile.<ConveyorEntity>entity().line = this;
        start = tile;

        Tile next = tile.getNearby((tile.rotation() + 2) % 4);

        if(next != null && next.rotation() == tile.rotation() && next.block() == tile.block()){
            next.<ConveyorEntity>entity().line.merge(this);
        }
    }

    /** adds a tile to the head of this line.*/
    public void addFirst(Tile tile){
        tile.<ConveyorEntity>entity().line = this;
        end = tile;
        Tile next = tile.getNearby(tile.rotation());

        //offset item at head by spacing
        if(items.size > 0){
            int src = items.peek();
            items.set(items.size - 1, ItemPos.get(ItemPos.item(src), ItemPos.space(src) + unitMult));
        }

        if(next != null && next.rotation() == tile.rotation() && next.block() == tile.block()){
            merge(next.<ConveyorEntity>entity().line);
        }
    }

    //TODO remove items
    public void remove(Tile tile){
        if(tile == end){ //tile is at end, move it back
            end = end.behind();
        }else if(tile == start){ //tile is at start, move it forward
            start = start.facing();
        }else if(start != end){ //only run this if there's still tiles left here
            if(seed != start){
                seed.entity.remove();
                seed = start;
                seed.entity.add();
            }

            Tile oldEnd = end;
            Tile newStart = tile.facing();

            ConveyorLine line = new ConveyorLine(newStart);
            line.end = oldEnd;
            line.start = newStart;
            //reparent tiles greater in index
            each(other -> {
                if(index(other) > index(tile)){
                    other.<ConveyorEntity>entity().line = line;
                }
            });

            int sum = 0;
            int removeLen = (line.length() + 1) * unitMult, reparentLen = line.length() * unitMult;
            int removeTo = 0, reparentTo = 0;

            for(int i = 0; i < items.size; i++){
                sum += ItemPos.space(items.items[i]);

                if(sum < reparentLen){
                    removeTo = i;
                    reparentTo = i;
                }else if(sum < removeLen){
                    removeTo = i;
                }else{
                    break;
                }
            }



            end = tile.behind();
        }
    }

    /** merges a line with another line, which must be directly in front.*/
    public void merge(ConveyorLine other){
        //remove other's entity to stop double updates
        other.seed.entity.remove();
        end = other.end;
        //reparent lines
        other.each(tile -> tile.<ConveyorEntity>entity().line = this);
        int toChange = items.size - 1;
        int lenOffset = 0;

        if(items.size != 0){
            int total = 0;
            for(int i = 0; i < other.items.size; i++){
                total += ItemPos.space(other.items.items[i]);
            }
            lenOffset = unitMult*other.length() - total;
        }

        items.addAll(other.items);

        //merge last item properly
        if(toChange > 0){
            int src = items.get(toChange);
            items.set(toChange, ItemPos.get(ItemPos.item(src), ItemPos.space(src) + lenOffset));
        }
    }

    public void update(){
        //nothing to update
        if(items.isEmpty()) return;

        //check item at front every frame to make sure it can move
        int hitem = items.peek();
        int hoffset = ItemPos.space(hitem);
        if(hoffset == 0){
            Tile next = end.facing();
            if(next != null) next = next.link();
            Item citem = content.item(hitem);
            //if the item can be handled, remove it
            if(next != null && next.block().handleAcceptItem(citem, next, end)){
                items.pop();
                index = 0; //reset to index 0, as items there can move now
            }else if(index == 0){
                //otherwise, go to the next item to move it forward since this one is stuck
                index++;
            }
        }else{
            index = 0;
        }

        //update other head item
        index = Mathf.clamp(index, 0, items.size - 1);
        int arridx = items.size - 1 - index;
        int head = items.get(arridx);
        int offset = ItemPos.space(head);
        byte item = ItemPos.item(head);

        //reached the end of the line for the head item
       if(offset <= itemSpacing && index != 0){ //hit edge of item up ahead, move up an index
            index ++;
        }else{
            //else, move item forward
            int moved = Math.max(1, (int)(Time.delta() * speed));
            items.set(arridx, ItemPos.get(item, Math.max(offset - moved, index == 0 ? 0 : itemSpacing)));
        }
    }

    public int length(){
        return Math.max(Math.abs(start.x - end.x), Math.abs(start.y - end.y))+1;
    }

    public int index(Tile tile){
        if(end.x == start.x){ //vertical
            return Math.max(tile.y - start.y, tile.y - end.y);
        }else{ //horizontal
            return Math.max(tile.x - start.x, tile.x - end.x);
        }
    }

    public Tile get(int idx){
        if(end.x == start.x){ //vertical
            return world.tile(start.x, start.y + Mathf.sign(end.y - start.y)*idx);
        }else{ //horizontal
            return world.tile(start.x + Mathf.sign(end.x - start.x)*idx, start.y);
        }
    }

    public void each(Consumer<Tile> cons){
        if(end.x == start.x){ //vertical
            int len = Math.abs(start.y - end.y);
            int sign = Mathf.sign(end.y - start.y);
            for(int i = 0; i <= len; i++){
                cons.accept(world.tile(start.x, start.y + sign*i));
            }
        }else{ //horizontal
            int len = Math.abs(start.x - end.x);
            int sign = Mathf.sign(end.x - start.x);
            for(int i = 0; i <= len; i++){
                cons.accept(world.tile(start.x + sign*i, start.y));
            }
        }
    }

    //size: 1 int
    @Struct
    class ItemPosStruct{
        /**item ID*/
        byte item;
        /**item position in conveyor line relative to the last item*/
        @StructField(24)
        int space;
    }
}
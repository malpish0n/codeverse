package dev.codeverse.render;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g3d.*;
import com.badlogic.gdx.graphics.g3d.attributes.*;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.math.collision.*;
import dev.codeverse.model.*;
import dev.codeverse.model.Data.*;
import java.util.*;

public final class CityRenderer implements AutoCloseable {
    public final PerspectiveCamera camera=new PerspectiveCamera(48,1280,800);
    private final ModelBatch batch=new ModelBatch();
    private final Environment light=new Environment();
    private final Model cube;
    private final Map<String,Building> buildings=new LinkedHashMap<>();
    private final java.util.List<ModelInstance> ground=new ArrayList<>();
    private final java.util.List<Model> owned=new ArrayList<>();
    private final Vector3 center=new Vector3(),target=new Vector3();
    public float yaw=42, pitch=44, distance=900, spacing=85;
    public boolean exploded=false,tour=false,reducedMotion=false,heat=false;
    private float explode=0,sceneRadius=500,time=0;
    private String selected;
    private Set<String> highlight=Set.of();
    private String isolated=null;
    private final Color cyan=Color.valueOf("4DE2E9"), violet=Color.valueOf("9B85FF");
    public static final class Building {
        public FileRevision file; public Cell cell; public ModelInstance body,roof;
        public float height, goal; public String change=""; public double hotspot;
        public BoundingBox bounds=new BoundingBox();
        Building(FileRevision f,Cell c,Model cube){file=f;cell=c;body=new ModelInstance(cube);roof=new ModelInstance(cube);height=0;goal=Data.height(f.loc());}
    }
    public CityRenderer(){
        cube=new ModelBuilder().createBox(1,1,1,new Material(ColorAttribute.createDiffuse(Color.WHITE)),VertexAttributes.Usage.Position|VertexAttributes.Usage.Normal);
        light.set(new ColorAttribute(ColorAttribute.AmbientLight,.52f,.60f,.72f,1));
        light.add(new DirectionalLight().set(.85f,.94f,1,-.5f,-1,-.3f));
        camera.near=1;camera.far=30000;
    }
    public void setScene(Snapshot snap,Map<String,Cell> cells,java.util.List<Change> changes,Map<String,Hotspot> hotspots,boolean reset){
        Set<String> live=new HashSet<>();Map<String,String> diff=new HashMap<>();
        for(var d:changes)diff.put(d.type().equals("DELETE")?d.oldPath():d.newPath(),d.type());
        for(var f:snap.files()){
            live.add(f.path());var b=buildings.computeIfAbsent(f.path(),p->new Building(f,cells.get(p),cube));
            b.file=f;b.cell=cells.get(f.path());b.goal=Data.height(f.loc());b.change=diff.getOrDefault(f.path(),"");b.hotspot=hotspots.getOrDefault(f.path(),new Hotspot(0,0,0,0)).score();
        }
        buildings.entrySet().removeIf(e->!live.contains(e.getKey()));
        rebuildGround(); if(reset)reset();
    }
    private void rebuildGround(){
        ground.clear();for(Model m:owned)m.dispose();owned.clear();
        Map<String,float[]> bounds=new TreeMap<>();
        for(var b:buildings.values()){var c=b.cell;var v=bounds.computeIfAbsent(b.file.module(),k->new float[]{Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE});v[0]=Math.min(v[0],c.x());v[1]=Math.min(v[1],c.z());v[2]=Math.max(v[2],c.x());v[3]=Math.max(v[3],c.z());}
        for(var v:bounds.values()){
            var plate=new ModelInstance(cube);plate.transform.setToTranslationAndScaling((v[0]+v[2])/2,-3,(v[1]+v[3])/2,v[2]-v[0]+35,4,v[3]-v[1]+35);
            plate.materials.get(0).set(ColorAttribute.createDiffuse(Color.valueOf("172A3A")));ground.add(plate);
        }
        if(buildings.isEmpty())return;
        BoundingBox box=new BoundingBox(); for(var b:buildings.values())box.ext(b.cell.x(),0,b.cell.z());box.getCenter(center);
        Vector3 dim=new Vector3();box.getDimensions(dim);sceneRadius=Math.max(160,Math.max(dim.x,dim.z)*.65f);
    }
    public void reset(){target.set(center);distance=sceneRadius*2.4f+200;yaw=42;pitch=44;}
    public void focus(String path){var b=buildings.get(path);if(b!=null){target.set(b.cell.x(),b.goal/2+b.cell.layer()*spacing*explode,b.cell.z());distance=230;selected=path;}}
    public void select(String path){selected=path;}
    public void highlight(Set<String> paths){highlight=paths;}
    public void isolate(String module){isolated=module;}
    public Collection<Building> buildings(){return buildings.values();}
    public Building selected(){return buildings.get(selected);}
    public Vector3 target(){return target;}
    public void orbit(float dx,float dy){tour=false;yaw-=dx*.3f;pitch=MathUtils.clamp(pitch+dy*.25f,12,85);}
    public void zoom(float amount){distance=MathUtils.clamp(distance*(1+amount*.09f),65,25000);}
    public void move(float forward,float right,float dt,boolean boost){
        float speed=(boost?3:1)*Math.max(80,distance*.6f)*dt;
        Vector3 f=new Vector3(camera.direction.x,0,camera.direction.z).nor(),r=new Vector3(f).crs(Vector3.Y);
        target.mulAdd(f,forward*speed).mulAdd(r,right*speed);
    }
    public void render(float dt,int left,int bottom,int width,int height){
        time+=dt;explode=MathUtils.lerp(explode,exploded?1:0,reducedMotion?1:Math.min(1,dt*7));
        if(tour&&!reducedMotion)yaw+=dt*9;
        float yr=yaw*MathUtils.degreesToRadians,pr=pitch*MathUtils.degreesToRadians;
        Vector3 pos=new Vector3(target.x+distance*MathUtils.cos(pr)*MathUtils.cos(yr),target.y+distance*MathUtils.sin(pr),target.z+distance*MathUtils.cos(pr)*MathUtils.sin(yr));
        camera.position.lerp(pos,reducedMotion?1:Math.min(1,dt*8));
        // Keep the camera outside visible building volumes.
        for(var b:buildings.values())if(Math.abs(camera.position.x-b.cell.x())<25&&Math.abs(camera.position.z-b.cell.z())<25)camera.position.y=Math.max(camera.position.y,b.height+b.cell.layer()*spacing*explode+20);
        camera.viewportWidth=width;camera.viewportHeight=height;camera.up.set(Vector3.Y);camera.lookAt(target);camera.update();
        Gdx.gl.glViewport(left,bottom,width,height);Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        batch.begin(camera);for(var g:ground)batch.render(g,light);
        for(var b:buildings.values()){
            b.height=MathUtils.lerp(b.height,b.goal,reducedMotion?1:Math.min(1,dt*8));float y=b.cell.layer()*spacing*explode;
            float w=b.cell.width();
            b.bounds.set(new Vector3(b.cell.x()-w/2,y,b.cell.z()-w/2),new Vector3(b.cell.x()+w/2,y+b.height,b.cell.z()+w/2));
            if(!camera.frustum.boundsInFrustum(b.bounds))continue;
            boolean sel=b.file.path().equals(selected);Color col=color(b);
            if(isolated!=null&&!isolated.equals(b.file.module()))col.mul(.25f);
            if(sel)col=cyan.cpy();else if(highlight.contains(b.file.path()))col=violet.cpy();
            b.body.materials.get(0).set(ColorAttribute.createDiffuse(col));
            b.body.transform.setToTranslationAndScaling(b.cell.x(),y+b.height/2,b.cell.z(),w,b.height,w);
            b.roof.materials.get(0).set(ColorAttribute.createDiffuse(sel?Color.WHITE:col.cpy().lerp(cyan,.45f)));
            b.roof.transform.setToTranslationAndScaling(b.cell.x(),y+b.height+.8f,b.cell.z(),w+1,1.6f,w+1);
            batch.render(b.body,light);batch.render(b.roof,light);
        }
        batch.end();Gdx.gl.glViewport(0,0,Gdx.graphics.getWidth(),Gdx.graphics.getHeight());
    }
    private Color color(Building b){
        if(b.change.equals("ADD"))return cyan.cpy();if(b.change.equals("DELETE"))return Color.valueOf("EC7885");if(!b.change.isEmpty())return violet.cpy();
        if(heat)return Color.valueOf("284F64").lerp(Color.valueOf("FFCC78"),(float)b.hotspot);
        return Color.valueOf("315E73").lerp(Color.valueOf("59889C"),(b.cell.layer()%5)/6f);
    }
    public String pick(int x,int y,int left,int bottom,int width,int height){
        Ray ray=camera.getPickRay(x,y,left,bottom,width,height);float nearest=Float.MAX_VALUE;String found=null;Vector3 hit=new Vector3();
        for(var b:buildings.values())if(Intersector.intersectRayBounds(ray,b.bounds,hit)){float d=hit.dst2(camera.position);if(d<nearest){nearest=d;found=b.file.path();}}
        return found;
    }
    public void close(){batch.dispose();cube.dispose();for(Model m:owned)m.dispose();}
}

package dev.codeverse.storage;

import com.google.gson.Gson;
import dev.codeverse.model.Data.Snapshot;
import java.nio.file.*;
import java.sql.*;
import java.io.*;
import java.util.*;

public final class Store implements AutoCloseable {
    private final Connection db;
    private final Path home;
    private final Gson json=new Gson();
    public Store(Path home) throws Exception {
        this.home=home;Files.createDirectories(home);
        db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("index-v1.sqlite"));
        try(var s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS snapshots (repo TEXT, sha TEXT, schema INTEGER, data TEXT, PRIMARY KEY(repo,sha,schema))");
        }
    }
    public synchronized Snapshot get(String repo,String sha) throws SQLException {
        try(var p=db.prepareStatement("SELECT data FROM snapshots WHERE repo=? AND sha=? AND schema=1")){
            p.setString(1,repo);p.setString(2,sha);try(var r=p.executeQuery()){return r.next()?json.fromJson(r.getString(1),Snapshot.class):null;}
        }
    }
    public synchronized void put(String repo,Snapshot snapshot) throws SQLException {
        try(var p=db.prepareStatement("INSERT OR REPLACE INTO snapshots VALUES (?,?,1,?)")){p.setString(1,repo);p.setString(2,snapshot.sha());p.setString(3,json.toJson(snapshot));p.executeUpdate();}
        try(var p=db.prepareStatement("DELETE FROM snapshots WHERE repo=? AND rowid NOT IN (SELECT rowid FROM snapshots WHERE repo=? ORDER BY rowid DESC LIMIT 32)")){p.setString(1,repo);p.setString(2,repo);p.executeUpdate();}
    }
    public synchronized void save(String name,Object value) throws IOException {
        if(!name.matches("[a-z-]+"))throw new IOException("Invalid settings name");
        Path temp=home.resolve(name+".tmp"), target=home.resolve(name+".json");
        Files.writeString(temp,json.toJson(value));
        try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    public synchronized <T>T load(String name,Class<T> type,T fallback){try{return json.fromJson(Files.readString(home.resolve(name+".json")),type);}catch(Exception e){return fallback;}}
    public Path home(){return home;}
    public synchronized void close() throws SQLException {db.close();}
}

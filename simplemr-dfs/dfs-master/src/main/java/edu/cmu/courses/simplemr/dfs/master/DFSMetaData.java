package edu.cmu.courses.simplemr.dfs.master;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cmu.courses.simplemr.dfs.DFSChunk;
import edu.cmu.courses.simplemr.dfs.DFSFile;
import edu.cmu.courses.simplemr.dfs.DFSNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class DFSMetaData {
    private static Logger LOG = LoggerFactory.getLogger(DFSMetaData.class);
    private String lock;
    private Map<String, DFSNode> dataNodes;
    private Map<String, Long> fileIndexes;
    private Map<Long, DFSFile> files;
    private Map<Long, DFSChunk> chunks;
    private EditLogger editLogger;

    public DFSMetaData(EditLogger editLogger){
        this.lock = "lock";
        this.dataNodes = new HashMap<String, DFSNode>();
        this.fileIndexes = new HashMap<String, Long>();
        this.files = new HashMap<Long, DFSFile>();
        this.chunks = new HashMap<Long, DFSChunk>();
        this.editLogger = editLogger;
    }

    public void updateDataNode(String serviceName,  int chunkNumber, boolean writeLog){
        DFSNode dataNode = null;
        synchronized (lock){
            if(dataNodes.containsKey(serviceName)){
                dataNode = dataNodes.get(serviceName);
                if(dataNode.getChunkNumber() != chunkNumber && writeLog){
                    dispatchLog(EditOperation.UPDATE_DATA_NODE, new Object[] {serviceName, chunkNumber});
                }
            } else {
                dataNode = new DFSNode(serviceName);
                dataNodes.put(serviceName, dataNode);
                if(writeLog){
                    dispatchLog(EditOperation.UPDATE_DATA_NODE, new Object[] {serviceName, chunkNumber});
                }
            }
            dataNode.setChunkNumber(chunkNumber);
            dataNode.setTimestamp(System.currentTimeMillis());
        }
    }

    public void removeDataNode(String serviceName, boolean writeLog){
        synchronized (lock){
            if(writeLog){
                dispatchLog(EditOperation.REMOVE_DATA_NODE, new Object[]{serviceName});
            }
            dataNodes.remove(serviceName);
        }
    }

    public DFSFile createFile(String fileName, int replicas, boolean writeLog){
        DFSFile file = null;
        synchronized (lock){
            if(writeLog){
                dispatchLog(EditOperation.DFS_CREATE_FILE, new Object[]{fileName, replicas});
            }
            if(fileIndexes.containsKey(fileName)){
                file = files.get(fileIndexes.get(fileName));
            } else {
                file = new DFSFile(DFSFile.maxId.incrementAndGet(), fileName, replicas);
                fileIndexes.put(fileName, file.getId());
                files.put(file.getId(), file);
            }
        }
        return file;
    }

    public DFSFile getFile(String fileName){
        DFSFile file = null;
        synchronized (lock){
            if(fileIndexes.containsKey(fileName)){
                file =  files.get(fileIndexes.get(fileName));
            }
        }
        return file;
    }

    public DFSFile[] listFiles(){
        DFSFile[] fileArray = null;
        synchronized (lock){
            fileArray = new DFSFile[files.size()];
            files.values().toArray(fileArray);
        }
        return fileArray;
    }

    public DFSChunk createChunk(long fileId, int offset, int size, boolean writeLog){
        DFSChunk chunk = null;
        synchronized (lock){
            if(writeLog){
                dispatchLog(EditOperation.DFS_CREATE_CHUNK, new Object[] {fileId, offset, size});
            }
            if(files.containsKey(fileId)){
                DFSFile file = files.get(fileId);
                DFSNode[] dataNodes = allocateDataNodes(file.getReplicas());
                if(dataNodes.length > 0){
                    chunk = new DFSChunk(DFSChunk.maxId.incrementAndGet(), fileId, offset, size, dataNodes);
                    file.addChunk(chunk);
                    chunks.put(chunk.getId(), chunk);
                }
            }
        }
        return chunk;
    }

    public void deleteFile(long fileId, boolean writeLog){
        synchronized (lock){
            if(writeLog){
                dispatchLog(EditOperation.DFS_DELETE_FILE, new Object[] {fileId});
            }
            if(files.containsKey(fileId)){
                DFSFile file = files.get(fileId);
                DFSChunk[] chunkArray = file.getChunks();
                for(DFSChunk chunk : chunkArray){
                    chunks.remove(chunk);
                }
                files.remove(fileId);
                fileIndexes.remove(file.getName());
            }
        }
    }

    public void recoveryFromLog(String logPath)
            throws IOException{
        File file = new File(logPath);
        if(file.exists()){
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line = null;
            ObjectMapper mapper = new ObjectMapper();
            while((line = reader.readLine()) != null){
                EditOperation operation = mapper.readValue(line, EditOperation.class);
                Object[] arguments = operation.getArguments();
                switch(operation.getType()){
                    case EditOperation.UPDATE_DATA_NODE:
                        updateDataNode((String)arguments[0], (Integer)arguments[1], false);
                        break;
                    case EditOperation.REMOVE_DATA_NODE:
                        removeDataNode((String)arguments[0], false);
                    case EditOperation.DFS_CREATE_FILE:
                        createFile((String)arguments[0], (Integer)arguments[1], false);
                        break;
                    case EditOperation.DFS_DELETE_FILE:
                        deleteFile((Long)arguments[0], false);
                        break;
                    case EditOperation.DFS_CREATE_CHUNK:
                        createChunk((Long)arguments[0], (Integer)arguments[1], (Integer)arguments[2], false);
                        break;
                    default:
                        break;
                }
            }
            reader.close();
        }
    }

    private void dispatchLog(byte operationType, Object[] arguments){
        EditOperation editOperation = new EditOperation(operationType, arguments);
        try {
            editLogger.addLog(editOperation);
        } catch (IOException e) {
            LOG.warn("can't write edit log", e);
        }
    }

    private DFSNode[] allocateDataNodes(int replicas){
        List<DFSNode> nodes = new ArrayList<DFSNode>();
        DFSNode[] results = null;
        DFSNode[] allNodes = new DFSNode[dataNodes.size()];
        dataNodes.values().toArray(allNodes);
        Arrays.sort(allNodes, new Comparator<DFSNode>() {
            @Override
            public int compare(DFSNode dfsNode, DFSNode dfsNode2) {
                return dfsNode.getChunkNumber() - dfsNode2.getChunkNumber();
            }
        });
        for(int i = 0; i < replicas && i < allNodes.length; i++){
            nodes.add(allNodes[i]);
        }
        results = new DFSNode[nodes.size()];
        nodes.toArray(results);
        return results;
    }
}

package TaoServer;

import TaoProxy.*;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;

import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.concurrent.Executors;

/**
 * @brief Class to represent a server for TaoStore
 */
public class TaoServer {
    RandomAccessFile mDiskFile;
    long mServerSize;
    int mTreeHeight;

    /**
     * @brief Default constructor
     */
    public TaoServer(long minServerSize) {
        try {
            mDiskFile = new RandomAccessFile(ServerConstants.ORAM_FILE, "rws");
            mTreeHeight = calculateHeight(minServerSize);
            mServerSize = calculateSize(mTreeHeight);

            mDiskFile.setLength(mServerSize);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public int getHeight() {
        return mTreeHeight;
    }

    public byte[] readPath(long pathID) {
        long bucketSize = ServerConstants.BUCKET_SIZE;
        byte[][] pathInBytes = new byte[mTreeHeight + 1][];
        try {
            boolean[] pathDirection = ServerUtility.getPathFromPID(pathID, mTreeHeight);

            long offset = 0;
            long index = 0;
            int pathIndex = 0;

            mDiskFile.seek(offset);
            pathInBytes[pathIndex] = new byte[(int)bucketSize];
            mDiskFile.readFully(pathInBytes[pathIndex]);
            pathIndex++;
            for (Boolean right : pathDirection) {
                if (right) {
                    offset = (2 * index + 2) * bucketSize;
                    index = offset / bucketSize;
                } else {
                    offset = (2 * index + 1) * bucketSize;
                    index = offset / bucketSize;
                }

                mDiskFile.seek(offset);

                pathInBytes[pathIndex] = new byte[(int)bucketSize];
                mDiskFile.readFully(pathInBytes[pathIndex]);

                pathIndex++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        byte[] returnData = pathInBytes[0];

        for (int i = 1; i < pathInBytes.length; i++) {
            returnData = Bytes.concat(returnData, pathInBytes[i]);
        }
        return returnData;
    }

    public void writePath(long pathID, byte[] data) {
        int bucketSize = (int) ServerConstants.BUCKET_SIZE;
        try {
            boolean[] pathDirection = ServerUtility.getPathFromPID(pathID, mTreeHeight);

            long offsetInDisk = 0;
            long indexIntoTree = 0;

            int dataIndexStart = 0;
            int dataIndexStop = bucketSize;

            mDiskFile.seek(offsetInDisk);
            mDiskFile.write(Arrays.copyOfRange(data, dataIndexStart, dataIndexStop));

            dataIndexStart += bucketSize;
            dataIndexStop += bucketSize;

            for (Boolean right : pathDirection) {
                if (right) {
                    offsetInDisk = (2 * indexIntoTree + 2) * bucketSize;
                    indexIntoTree = offsetInDisk / bucketSize;
                } else {
                    offsetInDisk = (2 * indexIntoTree + 1) * bucketSize;
                    indexIntoTree = offsetInDisk / bucketSize;
                }

                mDiskFile.seek(offsetInDisk);
                mDiskFile.write(Arrays.copyOfRange(data, dataIndexStart, dataIndexStop));

                dataIndexStart += bucketSize;
                dataIndexStop += bucketSize;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @brief Method to run proxy indefinitely
     */
    public void run() {
        try {
            // TODO: Properly configure to listen for messages from proxy
            // NOTE: currently code is just copy and pasted from internet
            // Create a thread pool for asynchronous sockets
            AsynchronousChannelGroup threadGroup =
                    AsynchronousChannelGroup.withFixedThreadPool(Constants.PROXY_THREAD_COUNT, Executors.defaultThreadFactory());

            // Create a channel
            AsynchronousServerSocketChannel channel =
                    AsynchronousServerSocketChannel.open(threadGroup).bind(new InetSocketAddress(12345));


            // Wait for incoming connections
            channel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel ch, Void att){
                    System.out.println("Server going to read");
                    // TODO: Finish
                    channel.accept(null, this);
                    ByteBuffer byteBuffer = ByteBuffer.allocate( Constants.MAX_BYTE_BUFFER_SERVER );
                    ch.read(byteBuffer, null, new CompletionHandler<Integer, Void>() {

                        @Override
                        public void completed(Integer result, Void attachment) {
                            byteBuffer.flip();
                            System.out.println("Server read something");
                            byte[] messageTypeBytes = new byte[4];

                            byteBuffer.get(messageTypeBytes);

                            // TODO: decryption of messageTypeBytes

                            int messageType = Ints.fromByteArray(messageTypeBytes);
                            System.out.println("Server thinks message type is " + messageType);
                            if (messageType == Constants.PROXY_READ_REQUEST) {
                                System.out.println("Server got it");
                                // parse the request
                                byte[] requestBytes = new byte[ProxyRequest.getProxyReadRequestSize()];
                                byteBuffer.get(requestBytes);

                                // TODO: decryption of requestBytes
                                ProxyRequest proxyReq = new ProxyRequest(requestBytes);

                                // Handle request
                                byte[] returnPathData = readPath(proxyReq.getPathID());

                                // TODO: encrypt and send back to proxy
                                Path returnPath = new Path(proxyReq.getPathID(), returnPathData);

                                ServerResponse response = new ServerResponse(returnPath);

                                ByteBuffer returnMessage = ByteBuffer.wrap(response.serializeAsMessage());
                                ch.write(returnMessage);
                            } else if (messageType == Constants.PROXY_WRITE_REQUEST) {
                                // parse the request
                                byte[] requestBytes = new byte[ProxyRequest.getProxyWriteRequestSize()];
                            }
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {

                        }
                    });
                }

                @Override
                public void failed(Throwable exc, Void att) {
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int calculateHeight(long storageSize) {
        // Keep track of the storage size
        long totalTreeSize = storageSize;
        long s = totalTreeSize % ServerConstants.TOTAL_BLOCK_SIZE;

        // Pad totalTreeSize so that we have a whole number of blocks
        if ((totalTreeSize % ServerConstants.TOTAL_BLOCK_SIZE) != 0) {
            totalTreeSize += ServerConstants.TOTAL_BLOCK_SIZE - (totalTreeSize % ServerConstants.TOTAL_BLOCK_SIZE);
        }

        // Calculate how many blocks we currently have
        long numBlocks = totalTreeSize / ServerConstants.TOTAL_BLOCK_SIZE;

        // Pad the number of blocks so we have a whole number of buckets
        if ((numBlocks % ServerConstants.NUM_BLOCKS_IN_BUCKET) != 0) {
            numBlocks += ServerConstants.NUM_BLOCKS_IN_BUCKET - (numBlocks % ServerConstants.NUM_BLOCKS_IN_BUCKET);
        }

        // Calculate the number of buckets we currently have
        long numBuckets = numBlocks / ServerConstants.NUM_BLOCKS_IN_BUCKET;

        // Calculate the height of our tree given the number of buckets we have
        return (int) Math.ceil((Math.log(numBuckets + 1) / Math.log(2)) - 1);
    }

    /**
     * @brief Method that will calculate the height and total storage requirements for the ORAM tree based on
     * storageSize, which is the minimum amount of data, in MB, which most be available for storage
     * @param treeHeight
     */
    public long calculateSize(int treeHeight) {
        // Given the height of tree, we now find the amount of buckets we need to make this a full binary tree
        long numBuckets = (long) Math.pow(2, treeHeight + 1) - 1;
        // We can now calculate the total size of the system
        return numBuckets * ServerConstants.BUCKET_SIZE;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Please provide desired size of storage in MB");
            return;
        }

        TaoServer server = new TaoServer(Long.parseLong(args[0]));
        server.run();
    }
}
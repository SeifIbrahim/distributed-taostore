package TaoServer;

import Configuration.ArgumentParser;
import Configuration.TaoConfigs;
import Configuration.Utility;
import Configuration.Unit;
import Messages.MessageCreator;
import Messages.MessageTypes;
import Messages.ProxyRequest;
import Messages.ServerResponse;
import TaoProxy.*;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @brief Class to represent a server for TaoStore
 */
public class TaoServer implements Server {

	// The total amount of server storage in bytes
	protected long mServerSize;

	// A MessageCreator to create different types of messages to be passed from
	// client, proxy, and server
	protected MessageCreator mMessageCreator;

	// The height of the tree stored on this server
	protected int mServerTreeHeight;
	// An array that will represent the tree, and keep track of the most recent
	// timestamp of a particular bucket
	// TODO this array may be to large to store in memory
	protected long[] mMostRecentTimestamp;

	// We will lock at a bucket level when operating on file
	protected ReentrantLock[] mBucketLocks;

	// Max threads for readPath tasks
	public final int READ_PATH_THREADS = 30;

	// Max threads for writeBack or initialize tasks
	public final int WRITE_BACK_THREADS = 100;

	// Executor for readPath tasks
	protected ExecutorService mReadPathExecutor;

	// Executor for writeBack and initialize tasks
	protected ExecutorService mWriteBackExecutor;

	// protected final Semaphore mFilePointersSemaphore = new Semaphore(1);

	// shared stack of file pointers
	protected Stack<RandomAccessFile> mFilePointers;

	protected Map<Integer, Long> mReadStartTimes;
	protected Map<Integer, Long> mWriteStartTimes;

	protected int mUnitId = 0;

	/**
	 * @brief Constructor
	 */
	public TaoServer(MessageCreator messageCreator, int unitId) {
		mUnitId = unitId;

		// Profiling
		mReadStartTimes = new ConcurrentHashMap<>();
		mWriteStartTimes = new ConcurrentHashMap<>();

		try {
			// Trace
			TaoLogger.logLevel = TaoLogger.LOG_OFF;

			// No passed in properties file, will use defaults
			TaoConfigs.initConfiguration();

			// Calculate the height of the tree that this particular server will store
			mServerTreeHeight = TaoConfigs.STORAGE_SERVER_TREE_HEIGHT;

			// Create array that will keep track of the most recent timestamp of each bucket
			// and the lock for each bucket
			int numBuckets = (2 << (mServerTreeHeight + 1)) - 1;
			mMostRecentTimestamp = new long[numBuckets];
			mBucketLocks = new ReentrantLock[numBuckets];

			// Initialize the locks
			for (int i = 0; i < mBucketLocks.length; i++) {
				mBucketLocks[i] = new ReentrantLock();
			}

			mReadPathExecutor = Executors.newFixedThreadPool(READ_PATH_THREADS);
			mWriteBackExecutor = Executors.newFixedThreadPool(WRITE_BACK_THREADS);

			// Calculate the total amount of space the tree will use
			mServerSize = TaoConfigs.STORAGE_SERVER_SIZE; // ServerUtility.calculateSize(mServerTreeHeight,
															// TaoConfigs.ENCRYPTED_BUCKET_SIZE);

			// Initialize file pointers
			mFilePointers = new Stack<>();
			for (int i = 0; i < WRITE_BACK_THREADS + READ_PATH_THREADS; i++) {
				RandomAccessFile diskFile = new RandomAccessFile(TaoConfigs.ORAM_FILE + mUnitId, "rwd");
				diskFile.setLength(mServerSize);
				mFilePointers.push(diskFile);
			}

			// Assign message creator
			mMessageCreator = messageCreator;
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}

	@Override
	public byte[] readPath(long pathID) {
		// Array of byte arrays (buckets expressed as byte array)
		byte[][] pathInBytes = new byte[mServerTreeHeight + 1][];

		// Get the directions for this path
		boolean[] pathDirection = Utility.getPathFromPID(pathID, mServerTreeHeight);

		// Variable to represent the offset into the disk file
		long offset = 0;

		// Index into logical array representing the ORAM tree
		long index = 0;

		// The current bucket we are looking for
		int currentBucket = 0;

		// Keep track of bucket size
		int mBucketSize = (int) TaoConfigs.ENCRYPTED_BUCKET_SIZE;

		// Allocate byte array for this bucket
		pathInBytes[currentBucket] = new byte[mBucketSize];

		// Keep track of the bucket we need to lock
		int bucketLockIndex = 0;

		// Grab a file pointer from shared pool
		/*
		 * while (true) { try { mFilePointersSemaphore.acquire(); break; } catch
		 * (InterruptedException e) { } }
		 */

		RandomAccessFile diskFile = null;
		synchronized (mFilePointers) {
			diskFile = mFilePointers.pop();
		}

		try {
			// Lock appropriate bucket
			mBucketLocks[bucketLockIndex].lock();

			// Seek into the file
			diskFile.seek(offset);

			// Read bytes from the disk file into the byte array for this bucket
			diskFile.readFully(pathInBytes[currentBucket]);

			// Increment the current bucket
			currentBucket++;

			// Visit the rest of the buckets
			for (Boolean right : pathDirection) {
				int previousBucketLockIndex = bucketLockIndex;

				// Navigate the array representing the tree
				if (right) {
					bucketLockIndex = 2 * bucketLockIndex + 2;
					offset = (2 * index + 2) * mBucketSize;
					index = offset / mBucketSize;
				} else {
					bucketLockIndex = 2 * bucketLockIndex + 1;
					offset = (2 * index + 1) * mBucketSize;
					index = offset / mBucketSize;
				}

				// Allocate byte array for this bucket
				pathInBytes[currentBucket] = new byte[mBucketSize];

				// Lock bucket
				mBucketLocks[bucketLockIndex].lock();

				// Unlock previous bucket
				mBucketLocks[previousBucketLockIndex].unlock();

				// Seek into file
				diskFile.seek(offset);

				// Read bytes from the disk file into the byte array for this bucket
				diskFile.readFully(pathInBytes[currentBucket]);

				// Increment the current bucket
				currentBucket++;
			}

			// Put first bucket into a new byte array representing the final return value
			byte[] returnData = pathInBytes[0];

			// Add every bucket into the new byte array
			for (int i = 1; i < pathInBytes.length; i++) {
				returnData = Bytes.concat(returnData, pathInBytes[i]);
			}

			// Return complete path
			returnData = Bytes.concat(Longs.toByteArray(pathID), returnData);
			return returnData;
		} catch (Exception e) {
			e.printStackTrace(System.out);
		} finally {
			// Unlock final bucket
			mBucketLocks[bucketLockIndex].unlock();

			// Release the file pointer lock
			synchronized (mFilePointers) {
				mFilePointers.push(diskFile);
			}
			// mFilePointersSemaphore.release();
		}

		// Return null if there is an error
		return null;
	}

	@Override
	public boolean writePath(int startIndex, byte[] data, long timestamp) {
		long pathID = Longs.fromByteArray(Arrays.copyOfRange(data, startIndex, startIndex + 8));

		TaoLogger.logDebug("Going to writepath " + pathID + " with timestamp " + timestamp);

		// Get the directions for this path
		boolean[] pathDirection = Utility.getPathFromPID(pathID, mServerTreeHeight);

		// Variable to represent the offset into the disk file
		long offsetInDisk = 0;

		// Index into logical array representing the ORAM tree
		long indexIntoTree = 0;

		// Keep track of bucket size
		int mBucketSize = (int) TaoConfigs.ENCRYPTED_BUCKET_SIZE;

		// Indices into the data byte array
		int dataIndexStart = startIndex + 8;

		// The current bucket we are looking for
		int bucketLockIndex = 0;

		// The current timestamp we are checking
		int timestampIndex = 0;

		// Grab a file pointer from shared pool
		/*
		 * while (true) { try { mFilePointersSemaphore.acquire(); break; } catch
		 * (InterruptedException e) { } }
		 */

		RandomAccessFile diskFile = null;
		synchronized (mFilePointers) {
			diskFile = mFilePointers.pop();
		}

		try {
			// Lock bucket
			mBucketLocks[bucketLockIndex].lock();

			// Check to see what the timestamp is for the root
			if (timestamp >= mMostRecentTimestamp[timestampIndex]) {
				// Seek into file
				diskFile.seek(offsetInDisk);

				// Write bucket to disk
				diskFile.write(data, dataIndexStart, mBucketSize);

				// Update timestamp
				mMostRecentTimestamp[timestampIndex] = timestamp;
			}

			// Increment indices
			dataIndexStart += mBucketSize;

			int level = 1;

			// Write the rest of the buckets
			for (Boolean right : pathDirection) {
				int previousBucketLockIndex = bucketLockIndex;

				// Navigate the array representing the tree
				if (right) {
					bucketLockIndex = 2 * bucketLockIndex + 2;
					timestampIndex = 2 * timestampIndex + 2;
					offsetInDisk = (2 * indexIntoTree + 2) * mBucketSize;
					indexIntoTree = offsetInDisk / mBucketSize;
				} else {
					bucketLockIndex = 2 * bucketLockIndex + 1;
					timestampIndex = 2 * timestampIndex + 1;
					offsetInDisk = (2 * indexIntoTree + 1) * mBucketSize;
					indexIntoTree = offsetInDisk / mBucketSize;
				}

				// Lock bucket
				mBucketLocks[bucketLockIndex].lock();

				// Unlock previous bucket
				mBucketLocks[previousBucketLockIndex].unlock();

				// Check to see that we have the newest version of bucket
				if (timestamp >= mMostRecentTimestamp[timestampIndex]) {
					// Seek into disk
					diskFile.seek(offsetInDisk);

					// Write bucket to disk
					diskFile.write(data, dataIndexStart, mBucketSize);

					// Update timestamp
					mMostRecentTimestamp[timestampIndex] = timestamp;
				}

				level++;

				// Increment indices
				dataIndexStart += mBucketSize;
			}

			// Return true, signaling that the write was successful
			return true;
		} catch (Exception e) {
			e.printStackTrace(System.out);
		} finally {
			// Unlock final bucket
			mBucketLocks[bucketLockIndex].unlock();

			// Release the file pointer lock
			synchronized (mFilePointers) {
				mFilePointers.push(diskFile);
			}
			// mFilePointersSemaphore.release();
		}

		// Return false, signaling that the write was not successful
		return false;
	}

	@Override
	public void run() {
		try {
			// Create a thread pool for asynchronous sockets
			AsynchronousChannelGroup threadGroup = AsynchronousChannelGroup
					.withFixedThreadPool(TaoConfigs.PROXY_THREAD_COUNT, Executors.defaultThreadFactory());

			Unit u = TaoConfigs.ORAM_UNITS.get(mUnitId);

			// Create a channel
			AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open(threadGroup);
			channel.bind(new InetSocketAddress(u.serverPort));

			// Asynchronously wait for incoming connections
			channel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
				@Override
				public void completed(AsynchronousSocketChannel proxyChannel, Void att) {
					// Start listening for other connections
					channel.accept(null, this);

					// Start up a new thread to serve this connection
					Runnable serializeProcedure = () -> serveProxy(proxyChannel);
					new Thread(serializeProcedure).start();
				}

				@Override
				public void failed(Throwable exc, Void att) {
					TaoLogger.logForce("Failed to accept connections");
					exc.printStackTrace(System.out);
					try {
						channel.close();
					} catch (IOException e) {
					}
				}
			});
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}

	/**
	 * @brief Method to serve a proxy connection
	 * @param channel
	 */
	protected void serveProxy(AsynchronousSocketChannel channel) {
		try {
			// Create byte buffer to use to read incoming message type and size
			ByteBuffer messageTypeAndSize = MessageUtility.createTypeReceiveBuffer();

			channel.read(messageTypeAndSize, null, new CompletionHandler<Integer, Void>() {
				public CompletionHandler<Integer, Void> outerCompletionHandler() {
					return this;
				}

				@Override
				public void completed(Integer result, Void attachment) {
					// Flip buffer for reading
					messageTypeAndSize.flip();

					// Figure out the type of the message
					int[] typeAndLength;
					try {
						typeAndLength = MessageUtility.parseTypeAndLength(messageTypeAndSize);
						messageTypeAndSize.clear();
					} catch (BufferUnderflowException e) {
						return;
					}

					int messageType = typeAndLength[0];
					int messageLength = typeAndLength[1];

					// Get the rest of the message
					ByteBuffer messageByteBuffer = ByteBuffer.allocate(messageLength);
					// Do one last asynchronous read to get the rest of the message
					channel.read(messageByteBuffer, null, new CompletionHandler<Integer, Void>() {
						@Override
						public void completed(Integer result, Void attachment) {
							// Make sure we read all the bytes
							if (messageByteBuffer.remaining() > 0) {
								channel.read(messageByteBuffer, null, this);
								return;
							}

							// Flip the byte buffer for reading
							messageByteBuffer.flip();

							// Get the rest of the bytes for the message
							byte[] requestBytes = new byte[messageLength];
							messageByteBuffer.get(requestBytes);

							// Create proxy read request from bytes
							ProxyRequest proxyReq = mMessageCreator.parseProxyRequestBytes(requestBytes);

							// Check message type
							if (messageType == MessageTypes.PROXY_READ_REQUEST) {
								// Profiling
								// mReadStartTimes.put(proxyReq.hashCode(), System.currentTimeMillis());

								mReadPathExecutor.submit(() -> {
									TaoLogger.logDebug("Serving a read request");
									// Read the request path
									long startTime = System.currentTimeMillis();

									byte[] returnPathData = readPath(proxyReq.getPathID());

									// long startTime = mReadStartTimes.remove(proxyReq.hashCode());
									long time = System.currentTimeMillis() - startTime;

									// Create a server response
									ServerResponse readResponse = mMessageCreator.createServerResponse();
									readResponse.setProcessingTime(time);
									readResponse.setPathID(proxyReq.getPathID());
									readResponse.setPathBytes(returnPathData);

									byte[] pathBytes = Longs.toByteArray(proxyReq.getPathID());
									pathBytes = Bytes.concat(pathBytes, returnPathData);

									// To be used for server response
									byte[] messageTypeAndLength = null;
									byte[] serializedResponse = null;

									// Create server response data and header
									serializedResponse = readResponse.serialize();
									messageTypeAndLength = Bytes.concat(Ints.toByteArray(MessageTypes.SERVER_RESPONSE),
											Ints.toByteArray(serializedResponse.length));

									// Create message to send to proxy
									ByteBuffer returnMessageBuffer = ByteBuffer
											.wrap(Bytes.concat(messageTypeAndLength, serializedResponse));

									try {
										// Write to proxy
										TaoLogger.logDebug(
												"Going to send response of size " + serializedResponse.length);
										while (returnMessageBuffer.remaining() > 0) {
											Future<Integer> writeToProxy = channel.write(returnMessageBuffer);
											writeToProxy.get();
										}
										TaoLogger.logDebug("Sent response");

										// Clear buffer
										returnMessageBuffer.clear();
									} catch (Exception e) {
										try {
											channel.close();
										} catch (IOException e1) {
										}
									} finally {
										// start processing the next message
										channel.read(messageTypeAndSize, null, outerCompletionHandler());
									}
								});
							} else if (messageType == MessageTypes.PROXY_WRITE_REQUEST) {
								// Profiling
								mWriteStartTimes.put(proxyReq.hashCode(), System.currentTimeMillis());

								mWriteBackExecutor.submit(() -> {
									TaoLogger.logDebug("Serving a write request");

									// If the write was successful
									boolean success = true;
									// if (proxyReq.getTimestamp() >= mTimestamp) {
									// mTimestamp = proxyReq.getTimestamp();

									// Get the data to be written
									byte[] dataToWrite = proxyReq.getDataToWrite();

									// Get the size of each path
									int pathSize = proxyReq.getPathSize();

									// Where to start the current write
									int startIndex = 0;

									// Variables to be used while writing
									long timestamp = proxyReq.getTimestamp();
									// Write each path
									while (startIndex < dataToWrite.length) {
										// Write path
										if (!writePath(startIndex, dataToWrite, timestamp)) {
											success = false;
										}

										// Increment start index
										startIndex += pathSize;
									}

									long startTime = mWriteStartTimes.remove(proxyReq.hashCode());
									long time = System.currentTimeMillis() - startTime;
									// long time = 0;

									// Create a server response
									ServerResponse writeResponse = mMessageCreator.createServerResponse();
									writeResponse.setProcessingTime(time);
									writeResponse.setIsWrite(success);

									// To be used for server response
									byte[] messageTypeAndLength = null;
									byte[] serializedResponse = null;

									// Create server response data and header
									serializedResponse = writeResponse.serialize();
									messageTypeAndLength = Bytes.concat(Ints.toByteArray(MessageTypes.SERVER_RESPONSE),
											Ints.toByteArray(serializedResponse.length));
									// Create message to send to proxy
									ByteBuffer returnMessageBuffer = ByteBuffer
											.wrap(Bytes.concat(messageTypeAndLength, serializedResponse));

									try {
										// Write to proxy
										TaoLogger.logDebug(
												"Going to send response of size " + serializedResponse.length);
										while (returnMessageBuffer.remaining() > 0) {
											Future<Integer> writeToProxy = channel.write(returnMessageBuffer);
											writeToProxy.get();
										}
										TaoLogger.logDebug("Sent write response");

										// Clear buffer
										returnMessageBuffer.clear();
									} catch (Exception e) {
										try {
											channel.close();
										} catch (IOException e1) {
										}
									} finally {
										// start processing the next message
										channel.read(messageTypeAndSize, null, outerCompletionHandler());
									}
								});
							} else if (messageType == MessageTypes.PROXY_INITIALIZE_REQUEST) {
								mWriteBackExecutor.submit(() -> {
									TaoLogger.logDebug("Serving an initialize request");
									if (mMostRecentTimestamp[0] != 0) {
										mMostRecentTimestamp = new long[(2 << (mServerTreeHeight + 1)) - 1];
									}

									// If the write was successful
									boolean success = true;
									// if (proxyReq.getTimestamp() >= mTimestamp) {
									// mTimestamp = proxyReq.getTimestamp();

									// Get the data to be written
									byte[] dataToWrite = proxyReq.getDataToWrite();

									// Get the size of each path
									int pathSize = proxyReq.getPathSize();

									// Where to start the current write
									int startIndex = 0;

									// Write each path
									while (startIndex < dataToWrite.length) {
										// Write path
										if (!writePath(startIndex, dataToWrite, 0)) {
											success = false;
										}

										// Increment start index
										startIndex += pathSize;
									}

									// To be used for server response
									byte[] messageTypeAndLength = null;
									byte[] serializedResponse = null;

									// Create a server response
									ServerResponse writeResponse = mMessageCreator.createServerResponse();
									writeResponse.setIsWrite(success);

									// Create server response data and header
									serializedResponse = writeResponse.serialize();
									messageTypeAndLength = Bytes.concat(Ints.toByteArray(MessageTypes.SERVER_RESPONSE),
											Ints.toByteArray(serializedResponse.length));

									// Create message to send to proxy
									ByteBuffer returnMessageBuffer = ByteBuffer
											.wrap(Bytes.concat(messageTypeAndLength, serializedResponse));

									try {
										// Write to proxy
										TaoLogger.logDebug(
												"Going to send response of size " + serializedResponse.length);
										while (returnMessageBuffer.remaining() > 0) {
											Future<Integer> writeToProxy = channel.write(returnMessageBuffer);
											writeToProxy.get();
										}
										TaoLogger.logDebug("Sent response");

										// Clear buffer
										returnMessageBuffer.clear();
									} catch (Exception e) {
										e.printStackTrace(System.out);
										try {
											channel.close();
										} catch (IOException e1) {
											e1.printStackTrace(System.out);
										}
									} finally {
										// start processing the next message
										channel.read(messageTypeAndSize, null, outerCompletionHandler());
									}
								});
							}
						}

						@Override
						public void failed(Throwable exc, Void attachment) {
							TaoLogger.logForce("Failed to read a message");
						}
					});
				}

				@Override
				public void failed(Throwable exc, Void attachment) {
					try {
						channel.close();
					} catch (IOException e1) {
					}
				}
			});
		} catch (Exception e) {
			try {
				channel.close();
			} catch (IOException e1) {
			}
		}
	}

	public static void main(String[] args) {
		try {
			// Parse any passed in args
			Map<String, String> options = ArgumentParser.parseCommandLineArguments(args);

			// Determine if the user has their own configuration file name, or just use the
			// default
			String configFileName = options.getOrDefault("config_file", TaoConfigs.USER_CONFIG_FILE);
			TaoConfigs.USER_CONFIG_FILE = configFileName;

			int unitId = Integer.parseInt(options.get("unit"));

			// Create server and run
			Server server = new TaoServer(new TaoMessageCreator(), unitId);
			server.run();
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}
}

package ibis.amuse;

import ibis.deploy.Job;
import ibis.ipl.Ibis;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.ReceivePortIdentifier;
import ibis.ipl.ReceiveTimedOutException;
import ibis.ipl.SendPort;
import ibis.ipl.WriteMessage;
import ibis.util.ThreadPool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Representation of worker at local machine running amuse script. Starts and
 * connects to a "RemoteWorker" to perform the actual work.
 * 
 * @author Niels Drost
 * 
 */
public class RemoteCodeInterface implements Runnable {

    public static final String MAGIC_STRING = "magic_string";

    private static final Logger logger = LoggerFactory.getLogger(RemoteCodeInterface.class);

    private static int nextID = 0;

    private final SocketChannel channel;

    private final String codeName;

    private final String codeDir;

    private final String hostname;

    private final int nrOfWorkers;

    private final int nrOfNodes;

    private final String id;

    private final Job job;

    private final Ibis ibis;

    private final ReceivePort receivePort;

    private final SendPort sendPort;

    private final ReceivePortIdentifier remotePort;

    private static String getNextID() {
        return Integer.toString(nextID++);
    }

    private static ReceivePortIdentifier receivePortAddress(ReceivePort receivePort, Job job) throws IOException {
        while (!job.isFinished()) {
            try {
                ReadMessage readMessage = receivePort.receive(1000);

                ReceivePortIdentifier remotePort = (ReceivePortIdentifier) readMessage.readObject();
                readMessage.finish();

                return remotePort;

            } catch (ReceiveTimedOutException t) {
                logger.warn("timeout on receiving message. Still waiting...");
            } catch (ClassNotFoundException t) {
                logger.warn("error on receiving message", t);
            }

        }

        throw new IOException("remote worker did not start properly", job.getException());
    }

    /*
     * Initializes worker by reading settings from amuse, deploying the worker
     * process on a (possibly remote) machine, and waiting for a connection from
     * the worker
     */
    RemoteCodeInterface(SocketChannel socket, Ibis ibis, Deployment deployment) throws Exception {
        this.channel = socket;
        this.ibis = ibis;

        if (logger.isDebugEnabled()) {
            logger.debug("New connection from " + socket.socket().getRemoteSocketAddress());
        }

        // read magic string, to make sure we are talking to amuse

        ByteBuffer magic = ByteBuffer.allocate(MAGIC_STRING.length());

        while (magic.hasRemaining()) {
            long read = channel.read(magic);

            if (read == -1) {
                throw new IOException("Connection closed on reading magic string");
            }
        }

        String receivedString = new String(magic.array(), "UTF-8");
        if (!receivedString.equals(MAGIC_STRING)) {
            throw new IOException("magic string " + MAGIC_STRING + " not received. Instead got: " + receivedString);
        }

        // read initialization call

        AmuseMessage initRequest = new AmuseMessage();
        initRequest.readFrom(channel);

        if (initRequest.getFunctionID() != AmuseMessage.FUNCTION_ID_INIT) {
            throw new IOException("first call to worker must be init function");
        }

        codeName = initRequest.getString(0);
        codeDir = initRequest.getString(1);
        hostname = initRequest.getString(2);

        nrOfWorkers = initRequest.getInteger(0);
        nrOfNodes = initRequest.getInteger(1);

        // get rid of "ugly" parts of id

        String idName = codeName;

        idName = idName.replace("_worker", "");
        idName = idName.replace("_sockets", "");

        id = idName + "-" + getNextID();

        // initialize ibis ports

        receivePort = ibis.createReceivePort(Daemon.portType, id.toString());
        receivePort.enableConnections();

        sendPort = ibis.createSendPort(Daemon.portType);

        // start deployment of worker (possibly on remote machine)

        job = deployment.deploy(codeName, codeDir, hostname, id, nrOfWorkers, nrOfNodes);

        // we expect a "hello" message from the worker. Will also check if
        // the job is still running
        remotePort = receivePortAddress(receivePort, job);

        sendPort.connect(remotePort);

        // do init function at remote worker so it can initialize the code

        // write init message
        WriteMessage writeMessage = sendPort.newMessage();
        initRequest.writeTo(writeMessage);
        writeMessage.finish();

        // read reply
        AmuseMessage initReply = new AmuseMessage();
        ReadMessage readMessage = receivePort.receive();
        initReply.readFrom(readMessage);
        readMessage.finish();

        if (initReply.getError() != null) {
            throw new IOException(initReply.getError());
        }

        // send reply to amuse
        initReply.writeTo(channel);

        ThreadPool.createNew(this, "Amuse worker");

        logger.info("New worker successfully started: " + this);
    }

    public void run() {
        AmuseMessage request = new AmuseMessage();
        AmuseMessage result = new AmuseMessage();
        long start, finish;

        boolean running = true;

        while (running) {
            start = System.currentTimeMillis();

            try {
                // logger.debug("wating for request...");
                request.readFrom(channel);

                // logger.debug("performing request " + request);

                if (request.getFunctionID() == AmuseMessage.FUNCTION_ID_STOP) {
                    // this will be the last call we perform
                    running = false;
                }

                if (job.isFinished()) {
                    throw new IOException("Remote worker no longer running", job.getException());
                }

                WriteMessage writeMessage = sendPort.newMessage();
                request.writeTo(writeMessage);
                writeMessage.finish();

                logger.debug("waiting for result");

                ReadMessage readMessage = receivePort.receive();
                result.readFrom(readMessage);
                readMessage.finish();

                if (result.getError() != null) {
                    logger.warn("Error while doing call at worker", result.getError());
                }

                logger.debug("request " + request.getCallID() + " handled, result: " + result);

                // forward result to the channel
                result.writeTo(channel);

                finish = System.currentTimeMillis();

                if (logger.isDebugEnabled()) {
                    logger.debug("call took " + (finish - start) + " ms");
                }
            } catch (IOException e) {
                logger.error("Error on handling call", e);
                // report error to amuse
                AmuseMessage errormessage = new AmuseMessage(request.getCallID(), request.getFunctionID(),
                        request.getCount(), e.getMessage());
                try {
                    errormessage.writeTo(channel);
                } catch (IOException e1) {
                    logger.error("Error while returning error message to amuse", e1);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    // IGNORE
                }
            }
        }
        logger.info(this + " ending");
        end();
        logger.info(this + " done!");
    }

    private void end() {
        try {
            ibis.registry().signal("end", remotePort.ibisIdentifier());
        } catch (IOException e) {
            logger.error("could not signal remote worker to leave");
        }

        try {
            sendPort.close();
        } catch (IOException e) {
            logger.error("Error closing sendport", e);
        }

        try {
            receivePort.close(1000);
        } catch (IOException e) {
            logger.error("Error closing receiveport", e);
        }

        try {
            job.waitUntilFinished();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public String toString() {
        return "Worker \"" + id + "\" running \"" + codeName + "\" on host " + hostname;
    }
}

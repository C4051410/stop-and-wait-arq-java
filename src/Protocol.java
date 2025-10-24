/*
 * Replace the following string of 0s with your student number
 * 000000000
 */
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.DatagramPacket;
import java.util.Scanner;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

public class Protocol {

    static final String  NORMAL_MODE="nm"   ;         // normal transfer mode: (for Part 1 and 2)
    static final String	 TIMEOUT_MODE ="wt"  ;        // timeout transfer mode: (for Part 3)
    static final String	 LOST_MODE ="wl"  ;           // lost Ack transfer mode: (for Part 4)
    static final int DEFAULT_TIMEOUT =1000  ;         // default timeout in milliseconds (for Part 3)
    static final int DEFAULT_RETRIES =4  ;            // default number of consecutive retries (for Part 3)
    public static final int MAX_Segment_SIZE = 4096;  //the max segment size that can be used when creating the received packet's buffer

    /*
     * The following attributes control the execution of the transfer protocol and provide access to the
     * resources needed for the transfer
     * */

    private InetAddress ipAddress;      // the address of the server to transfer to. This should be a well-formed IP address.
    private int portNumber; 		    // the  port the server is listening on
    private DatagramSocket socket;      // the socket that the client binds to

    private File inputFile;            // the client-side CSV file that has the readings to transfer
    private String outputFileName ;    // the name of the output file to create on the server to store the readings
    private int maxPatchSize;		   // the patch size - no of readings to be sent in the payload of a single Data segment

    private Segment dataSeg   ;        // the protocol Data segment for sending Data segments (with payload read from the csv file) to the server
    private Segment ackSeg  ;          // the protocol Ack segment for receiving ACK segments from the server

    private int timeout;              // the timeout in milliseconds to use for the protocol with timeout (for Part 3)
    private int maxRetries;           // the maximum number of consecutive retries (retransmissions) to allow before exiting the client (for Part 3)(This is per segment)
    private int currRetry;            // the current number of consecutive retries (retransmissions) following an Ack loss (for Part 3)(This is per segment)

    private int fileTotalReadings;    // number of all readings in the csv file
    private int sentReadings;         // number of readings successfully sent and acknowledged
    private int totalSegments;        // total segments that the client sent to the server

    // Added for Part 2 & 4 to manage file reading state
    private Scanner fileScanner;
    // Added for Part 4 to track the sequence number of the last correctly received data segment
    private int expectedSeqNum = 1;
    private int lastCorrectSeqNum = -1; // -1 to indicate no segment received yet
    private long totalBytesReceived = 0;
    private long totalUsefulBytes = 0;


    // Shared Protocol instance so Client and Server access and operate on the same values for the protocol’s attributes (the above attributes).
    public static Protocol instance = new Protocol();

    /**************************************************************************************************************************************
     **************************************************************************************************************************************
     * For this assignment, you have to implement the following methods:
     *		sendMetadata()
     * readandSend()
     * receiveAck()
     * startTimeoutWithRetransmission()
     *		receiveWithAckLoss()
     * Do not change any method signatures, and do not change any other methods or code provided.
     ***************************************************************************************************************************************
     **************************************************************************************************************************************/

    /* * Helper method to count lines in the CSV file
     */
    private int countReadings() {
        int count = 0;
        try (Scanner fileIn = new Scanner(instance.inputFile)) {
            while (fileIn.hasNextLine()) {
                fileIn.nextLine();
                count++;
            }
        } catch (FileNotFoundException e) {
            System.err.println("Error: Input file not found during reading count.");
        }
        return count;
    }

    /* * Helper method to send a Segment (used for Meta and Data segments)
     */
    private void sendSegment(Segment segment) throws IOException {
        // Serialize the Segment object
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream os = new ObjectOutputStream(outputStream);
        os.writeObject(segment);
        byte[] data = outputStream.toByteArray();

        // Create the DatagramPacket
        DatagramPacket sendPacket = new DatagramPacket(data, data.length, instance.ipAddress, instance.portNumber);

        // Send the packet
        instance.socket.send(sendPacket);

        // Update total segments sent
        instance.totalSegments++;

        // In Part 4, we track total bytes sent for efficiency calculation
        if (segment.getType() == SegmentType.Data) {
            instance.totalBytesReceived += data.length; // This attribute is misused as totalBytesSent in client-side, but let's stick to the server-side logic in Part 4. The specification suggests tracking total bytes *received* on the server. I will stick to the Part 4 requirements and track bytes in receiveWithAckLoss.
        }
    }

    /**
     * Part 1: Sends protocol metadata to the server.
     * The metadata includes the total number of readings, output file name, and patch size.
     */
    public void sendMetadata()   {
        try {
            // 1. Count the total number of readings in the CSV file and store in fileTotalReadings
            instance.fileTotalReadings = countReadings();

            // 2. Retrieve output file name and patch size (already in attributes) [cite: 96]

            // 3. Assemble the information into the payload (Total readings, output file name, patch size)
            String payload = instance.fileTotalReadings + "," + instance.outputFileName + "," + instance.maxPatchSize;

            // 4. Create the Meta segment (seqNum=0, type=Meta)
            // The size is the length of the payload. The Segment constructor calculates the checksum.
            Segment metaSeg = new Segment(0, SegmentType.Meta, payload, payload.length());

            // 5. Print status message [cite: 100]
            System.out.println("CLIENT: META [SEQ#" + metaSeg.getSeqNum() + "] (Number of readings:" +
                    instance.fileTotalReadings + ", file name:" + instance.outputFileName +
                    ", patch size:" + instance.maxPatchSize + ")");

            // 6. Send the segment to the server
            sendSegment(metaSeg);

            // Initialise fileScanner for Part 2 and 3
            instance.fileScanner = new Scanner(instance.inputFile);

        } catch (IOException e) {
            // 7. Deal with error in sending, close open resources, and exit [cite: 101]
            System.err.println("CLIENT: Error sending metadata: " + e.getMessage());
            if (instance.socket != null) {
                instance.socket.close();
            }
            System.exit(1);
        }
    }


    /**
     * Part 2: Reads and sends the next data segment (dataSeg) to the server.
     */
    public void readAndSend() {
        if (instance.fileScanner == null) {
            System.err.println("CLIENT: File scanner not initialized. Exiting.");
            System.exit(1);
        }

        // Check if all readings have been sent and acknowledged (handled in receiveAck, but good for safety)
        if (instance.sentReadings >= instance.fileTotalReadings) {
            return; // Nothing more to send
        }

        // 1. Read up to maxPatchSize readings from the CSV file [cite: 134]
        StringBuilder payloadBuilder = new StringBuilder();
        int readingsRead = 0;
        while (instance.fileScanner.hasNextLine() && readingsRead < instance.maxPatchSize) {
            String line = instance.fileScanner.nextLine();

            // The data.csv lines are: A,1720456123,12.4,75.2,1013.6
            String[] parts = line.split(",");

            // Create a Reading object (values are [temp, humidity, pressure])
            float[] values = new float[3];
            try {
                values[0] = Float.parseFloat(parts[2].trim());
                values[1] = Float.parseFloat(parts[3].trim());
                values[2] = Float.parseFloat(parts[4].trim());
            } catch (NumberFormatException e) {
                System.err.println("CLIENT: Error parsing reading values: " + line);
                System.exit(1);
            }

            Reading reading = new Reading(parts[0].trim(), Long.parseLong(parts[1].trim()), values);

            if (readingsRead > 0) {
                // Multiple readings are separated by a semicolon (;) with no spaces
                payloadBuilder.append(";");
            }
            // Use toString() from Reading.java to get the payload content [cite: 143]
            payloadBuilder.append(reading.toString());
            readingsRead++;
        }

        String payload = payloadBuilder.toString();

        // If no readings were read (e.g., file was empty or at the end), we shouldn't send anything.
        if (readingsRead == 0 && instance.sentReadings < instance.fileTotalReadings) {
            // This case should be covered by the check above, but in case of file corruption.
            System.err.println("CLIENT: Could not read any readings. Exiting.");
            System.exit(1);
            return;
        }

        // 2. Set the correct segment type, sequence number, and size
        // The seqNum alternates between 1 and 0. First Data segment is 1.
        int seqNum = (instance.dataSeg.getSeqNum() == 1) ? 0 : 1;

        // If this is a retransmission (Part 3), keep the same sequence number and payload
        // For the initial send, we create a new segment
        if(instance.currRetry == 0 || instance.dataSeg.getPayLoad() == null) {
            instance.dataSeg = new Segment(seqNum, SegmentType.Data, payload, payload.length());
            // For Part 4 efficiency calculation: track the total size of the original data segments
            if(instance.totalUsefulBytes == 0) {
                // Total bytes of useful data is the sum of sizes of the original Data segments [cite: 222]
                instance.totalUsefulBytes = (long) Math.ceil((float)instance.fileTotalReadings/instance.maxPatchSize) * payload.length();
            }
        } else {
            // This is a retransmission, reuse the old segment's sequence number and payload
            // The only thing we need to update is the totalSegments count in sendSegment
            // The segment is already set up in the previous iteration of the client loop
            seqNum = instance.dataSeg.getSeqNum(); // keep the same seqNum
        }

        try {
            // 3. Print status message [cite: 140]
            System.out.println("CLIENT: Send: DATA [SEQ#" + instance.dataSeg.getSeqNum() + "] (size:" + instance.dataSeg.getSize() +
                    ", crc: " + instance.dataSeg.getChecksum() + ", content:" + instance.dataSeg.getPayLoad() + ")");

            // 4. Transmit the data segment
            sendSegment(instance.dataSeg);

        } catch (IOException e) {
            System.err.println("CLIENT: Error sending data segment: " + e.getMessage());
            if (instance.socket != null) {
                instance.socket.close();
            }
            System.exit(1);
        }
    }

    /**
     * Part 2: Receives the current Ack segment (ackSeg) from the server.
     * Returns true if the sequence number is correct, false otherwise.
     */
    public boolean receiveAck() {
        try {
            // 1. Prepare to receive the ACK segment
            byte[] buf = new byte[MAX_Segment_SIZE];
            DatagramPacket incomingPacket = new DatagramPacket(buf, buf.length);

            // Wait for the ACK
            instance.socket.receive(incomingPacket);

            // Deserialize the received Segment
            byte[] data = incomingPacket.getData();
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            ObjectInputStream is = new ObjectInputStream(in);

            try {
                instance.ackSeg = (Segment) is.readObject();
            } catch (ClassNotFoundException e) {
                System.err.println("CLIENT: ClassNotFoundException during ACK reception: " + e.getMessage());
                return false;
            }

            // The expected sequence number is the one from the data segment we just sent
            int expectedSeqNum = instance.dataSeg.getSeqNum();

            // 2. Check if the segment is an ACK and if its sequence number is correct
            if (instance.ackSeg.getType() == SegmentType.Ack && instance.ackSeg.getSeqNum() == expectedSeqNum) {

                // Print status message [cite: 151]
                System.out.println("CLIENT: RECIEVE: ACK [SEQ#" + instance.ackSeg.getSeqNum() + "]");

                // If the ACK is correct, update sentReadings
                // The number of readings in the payload is equal to the number of ';' separators + 1
                int readingsInSegment = 0;
                String payload = instance.dataSeg.getPayLoad();
                if (payload != null && !payload.isEmpty()) {
                    readingsInSegment = payload.split(";").length;
                }
                instance.sentReadings += readingsInSegment; //

                // Reset current retry count for Part 3
                instance.currRetry = 0;

                // 3. Check for completion and exit
                if (instance.sentReadings >= instance.fileTotalReadings) {
                    System.out.println("Total segments: " + instance.totalSegments);
                    if(instance.fileScanner != null) instance.fileScanner.close();
                    instance.socket.close();
                    System.exit(0);
                }

                return true;
            } else {
                // Sequence number is incorrect or not an ACK
                System.out.println("CLIENT: Received segment is not the expected ACK or has an incorrect sequence number. Expected SEQ#" +
                        expectedSeqNum + ", Received SEQ#" + instance.ackSeg.getSeqNum());
                return false;
            }

        } catch (SocketTimeoutException e) {
            // For normal mode, timeout shouldn't happen, but in a real-world scenario it might.
            // The Client.java implementation of sendNormal() handles returning false by exiting. [cite: 131]
            System.out.println("CLIENT: Timeout while waiting for ACK. This should not happen in NORMAL_MODE.");
            return false;
        } catch (IOException e) {
            System.err.println("CLIENT: IOException during ACK reception: " + e.getMessage());
            if (instance.socket != null) {
                instance.socket.close();
            }
            System.exit(1);
            return false; // Unreachable but for compiler happiness
        }
    }

    /**
     * Part 3: Starts a timer and re-transmits the Data segment if the ACK is lost.
     * Uses a loop to wait for ACK with timeout and retries.
     */
    public void startTimeoutWithRetransmission()   {
        try {
            // Set the socket timeout
            instance.socket.setSoTimeout(instance.timeout);

            // Loop to continuously try receiving the ACK with timeout
            while(instance.currRetry < instance.maxRetries) {
                try {
                    // Attempt to receive the ACK
                    boolean ackReceived = receiveAck();

                    if (ackReceived) {
                        // ACK received and sequence number is correct. Exit the loop.
                        return;
                    } else {
                        // ACK received but sequence number is incorrect (Shouldn't happen with Server.java logic, but for robustness)
                        // Treat it as a loss in this context and retransmit the current segment.
                        throw new SocketTimeoutException("Incorrect ACK received");
                    }

                } catch (SocketTimeoutException e) {
                    // Timeout expired, ACK was lost or delayed [cite: 183]
                    instance.currRetry++; // Update consecutive retries [cite: 186]

                    System.out.println("CLIENT: TIMEOUT ALERT");

                    if (instance.currRetry >= instance.maxRetries) {
                        // Maximum retries exceeded, terminate the program
                        System.err.println("CLIENT: Maximum retries (" + instance.maxRetries + ") exceeded for segment SEQ#" + instance.dataSeg.getSeqNum() + ". Exiting.");
                        System.out.println("Total segments: " + instance.totalSegments);
                        if(instance.fileScanner != null) instance.fileScanner.close();
                        instance.socket.close();
                        System.exit(1);
                        return;
                    }

                    // Retransmit the same Data segment
                    System.out.println("CLIENT: Re-sending the same segment again, current retry " + instance.currRetry); // [cite: 190]

                    // Re-send the segment (readAndSend() handles the retransmission by keeping the old segment's payload and seqNum)
                    // The segment itself is already in dataSeg from the previous call to readAndSend()
                    readAndSend(); // This reuses the existing dataSeg
                }
            }
        } catch (SocketException e) {
            System.err.println("CLIENT: Socket error in startTimeoutWithRetransmission: " + e.getMessage());
            if (instance.socket != null) {
                instance.socket.close();
            }
            System.exit(1);
        }
    }


    /**
     * Part 4: Used by the server to receive the Data segment in Lost Ack mode,
     * simulating ACK loss and handling duplicate segments.
     */
    public void receiveWithAckLoss(DatagramSocket serverSocket, float loss)  {
        byte[] buf = new byte[MAX_Segment_SIZE];
        // Create a temporary list to store the readings, similar to receiveNormal()
        List<String> receivedLines = new ArrayList<>();
        int readingCount = 0;
        int lastAckSeqNum = -1; // Sequence number of the last ACK sent (needed for resending ACK on duplicate)

        try {
            // Set a timeout so the server doesn't hang if the client exits after max retries [cite: 218, 219]
            serverSocket.setSoTimeout(2000);

            // While loop to continuously receive Data segments
            while (true) {
                DatagramPacket incomingPacket = new DatagramPacket(buf, buf.length);
                Segment serverDataSeg = new Segment();
                int packetSize = 0; // The size of the received UDP packet

                try {
                    // 1. Receive from the client
                    serverSocket.receive(incomingPacket);
                    packetSize = incomingPacket.getLength();

                    // Deserialize the received Segment
                    byte[] data = incomingPacket.getData();
                    ByteArrayInputStream in = new ByteArrayInputStream(data);
                    ObjectInputStream is = new ObjectInputStream(in);

                    // read and then print the content of the segment
                    try {
                        serverDataSeg = (Segment) is.readObject();
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                        continue; // Skip this segment
                    }

                } catch (SocketTimeoutException e) {
                    // Timeout: client probably exited after max retries [cite: 218, 219]
                    System.out.println("SERVER: Timeout reached. Assuming client has exited after max retries.");
                    break; // Exit the while loop
                }

                // Update total bytes received (including retransmitted and corrupted) [cite: 222]
                instance.totalBytesReceived += packetSize;

                // Print the content of the segment
                System.out.println("SERVER: Receive: DATA [SEQ#"+ serverDataSeg.getSeqNum()+ "]("+"size:"+serverDataSeg.getSize()+", crc: "+serverDataSeg.getChecksum()+
                        ", content:"  + serverDataSeg.getPayLoad()+")");

                // 2. Validate checksum (same as receiveNormal) [cite: 80, 199]
                long calculatedChecksum = serverDataSeg.calculateChecksum();

                if (serverDataSeg.getType() == SegmentType.Data && calculatedChecksum != serverDataSeg.getChecksum()) {
                    // Corrupted segment: do not send ACK, wait for client timeout/retransmission
                    System.out.println("SERVER: Calculated checksum is " + calculatedChecksum + "  INVALID");
                    System.out.println("SERVER: Not sending any ACK ");
                    System.out.println("*************************** ");
                    continue; // Wait for the next packet
                }

                // Valid segment received (either original or duplicate)
                System.out.println("SERVER: Calculated checksum is " + calculatedChecksum + "  VALID");

                // 3. Check for duplication [cite: 213, 214]
                // The expectedSeqNum is what we're waiting for (1 or 0).
                // lastCorrectSeqNum is the sequence number of the last segment that was *correctly* received (not necessarily the one whose ACK was successfully sent).
                int currentSeqNum = serverDataSeg.getSeqNum();

                if (currentSeqNum == instance.expectedSeqNum) {
                    // Correctly received (Original) segment

                    // 3.1. Process the segment payload (write to temporary list) [cite: 216]
                    String[] lines = serverDataSeg.getPayLoad().split(";");
                    receivedLines.add("Segment ["+ serverDataSeg.getSeqNum() + "] has "+ lines.length + " Readings");
                    receivedLines.addAll(Arrays.asList(lines));
                    receivedLines.add("");

                    // 3.2. Update tracking variables
                    readingCount += lines.length;
                    instance.lastCorrectSeqNum = currentSeqNum; // Update the last correctly received seqNum
                    instance.expectedSeqNum = (currentSeqNum == 1) ? 0 : 1; // Toggle the expected seqNum

                    // 3.3. Attempt to send ACK (may be lost)
                    attemptSendAck(serverSocket, incomingPacket.getAddress(), incomingPacket.getPort(), currentSeqNum, loss);
                    lastAckSeqNum = currentSeqNum; // Record the sequence number of the ACK we *attempted* to send

                } else if (currentSeqNum == instance.lastCorrectSeqNum) {
                    // Duplicate segment detected (e.g., ACK for last segment was lost, client retransmitted) [cite: 201, 202, 214]

                    System.out.println("Duplicate DATA is detected"); [cite: 217]
                    // Do not write to temporary list [cite: 214]

                    // Resend the ACK for the last correctly received Data segment [cite: 215]
                    // This segment's sequence number is currentSeqNum, which is also lastCorrectSeqNum
                    System.out.println("Sending an Ack of the previous segment"); [cite: 217]
                    attemptSendAck(serverSocket, incomingPacket.getAddress(), incomingPacket.getPort(), currentSeqNum, loss);
                    lastAckSeqNum = currentSeqNum; // Record the sequence number of the ACK we *attempted* to send

                } else {
                    // Unexpected sequence number (e.g., client sent next segment before ACK, or out of sync)
                    // In Stop-and-Wait, this should not happen if the client is correct.
                    // We'll treat it as a duplicate of the last ACKed segment if the client has somehow gotten ahead.
                    // If lastCorrectSeqNum is -1, it means this is the first segment and it has the wrong sequence number (should be 1).
                    if (instance.lastCorrectSeqNum != -1) {
                        // The server got a segment it wasn't expecting. The client is likely out of sync.
                        // The safest bet is to re-ACK the last *correctly received* one (lastCorrectSeqNum)
                        System.out.println("SERVER: Unexpected sequence number. Expected: " + instance.expectedSeqNum +
                                ", Received: " + currentSeqNum + ". Resending ACK for last correct segment SEQ#" + instance.lastCorrectSeqNum);
                        attemptSendAck(serverSocket, incomingPacket.getAddress(), incomingPacket.getPort(), instance.lastCorrectSeqNum, loss);
                        lastAckSeqNum = instance.lastCorrectSeqNum;
                    } else {
                        // First segment, but seqNum is 0 (should be 1). Ignore it.
                        System.out.println("SERVER: First segment has incorrect sequence number (0 instead of 1). Ignoring.");
                    }
                }


                // 4. Check for completion and exit [cite: 85]
                if (instance.getOutputFileName() != null && readingCount >= instance.getFileTotalReadings()) {
                    // Write readings to file [cite: 216]
                    Server.writeReadingsToFile(receivedLines, instance.getOutputFileName());

                    // Calculate and print efficiency [cite: 221, 223, 224]
                    // Total Useful Bytes is the original file size, calculated in readAndSend
                    // The segment payload includes the full string representation of the Reading objects.
                    // In Part 4, the useful bytes is simply the original total data segment size (calculated in readAndSend)
                    // The useful bytes count should be accurate from the client-side's first readAndSend

                    // To be robust, if totalUsefulBytes was not set (e.g., due to testing Part 4 first), we can approximate.
                    if(instance.totalUsefulBytes == 0) {
                        // Approximate the useful bytes from the first segment's size and total segments expected
                        int numSegments = (int) Math.ceil((float)instance.fileTotalReadings/instance.maxPatchSize);
                        if (numSegments > 0) {
                            // Assuming the first segment is representative of non-final segment size.
                            // This is a rough approximation if the last segment is smaller.
                            instance.totalUsefulBytes = (long) numSegments * lines.length;
                            System.err.println("Warning: Total Useful Bytes was 0. Using approximation.");
                        } else {
                            instance.totalUsefulBytes = 1; // Avoid division by zero
                        }
                    }

                    // Efficiency calculation [cite: 224]
                    double efficiency = (double)instance.totalUsefulBytes / instance.totalBytesReceived * 100;

                    System.out.println("Total Bytes: " + instance.totalBytesReceived);
                    System.out.println("Useful Bytes: " + instance.totalUsefulBytes);
                    System.out.println("Efficiency: " + String.format("%.14f", efficiency) + "%");

                    break; // Exit the while loop
                }

                // If the ACK of the last received Data segment is lost, the server should not hang.
                // This is covered by the overall SocketTimeoutException catch and break. [cite: 220]
            }
        } catch (IOException e) {
            System.err.println("SERVER: IOException in receiveWithAckLoss: " + e.getMessage());
        } finally {
            // Close the socket
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }

    /* * Helper method for Part 4 to simulate ACK loss before sending an ACK.
     */
    private void attemptSendAck(DatagramSocket serverSocket, InetAddress address, int port, int seqNum, float loss) throws IOException {
        // Uses the provided isLost() method to check if the ACK should be lost [cite: 206, 207]
        if (isLost(loss)) {
            // The ACK is lost [cite: 211]
            System.out.println("SERVER: Simulating ACK loss. ACK[SEQ#" + seqNum + "] is lost."); [cite: 212]
            System.out.println("***");
            System.out.println("***");
        } else {
            // The ACK is sent as normal [cite: 208]
            // Use the static helper method from Server.java [cite: 225, 227]
            Server.sendAck(serverSocket, address, port, seqNum);
        }
    }


    /*************************************************************************************************************************************
     **************************************************************************************************************************************
     **************************************************************************************************************************************
     These methods are implemented for you .. Do NOT Change them
     **************************************************************************************************************************************
     **************************************************************************************************************************************
     **************************************************************************************************************************************/
    /* * This method initialises ALL the 14 attributes needed to allow the Protocol methods to work properly
     */
    public void initProtocol(String hostName , String portNumber, String fileName, String outputFileName, String batchSize) throws UnknownHostException, SocketException {
        instance.ipAddress = InetAddress.getByName(hostName);
        instance.portNumber = Integer.parseInt(portNumber);
        instance.socket = new DatagramSocket();

        instance.inputFile = checkFile(fileName); //check if the CSV file does exist
        instance.outputFileName =  outputFileName;
        instance.maxPatchSize= Integer.parseInt(batchSize);

        instance.dataSeg = new Segment(); //initialise the data segment for sending readings to the server
        instance.ackSeg = new Segment();  //initialise the ack segment for receiving Acks from the server

        instance.fileTotalReadings = 0;
        instance.sentReadings=0;
        instance.totalSegments =0;

        instance.timeout = DEFAULT_TIMEOUT;
        instance.maxRetries = DEFAULT_RETRIES;
        instance.currRetry = 0;

        // Initialise state for Part 4
        instance.expectedSeqNum = 1; // Next expected sequence number is 1 for the first Data segment
        instance.lastCorrectSeqNum = -1;
        instance.totalBytesReceived = 0;
        instance.totalUsefulBytes = 0;
    }


    /* * check if the csv file does exist before sending it
     */
    private static File checkFile(String fileName)
    {
        File file = new File(fileName);
        if(!file.exists()) {
            System.out.println("CLIENT: File does not exists");
            System.out.println("CLIENT: Exit ..");
            System.exit(0);
        }
        return file;
    }

    /* * returns true with the given probability to simulate network errors (Ack loss)(for Part 4)
     */
    private static Boolean isLost(float prob)
    {
        double randomValue = Math.random();  //0.0 to 99.9
        return randomValue <= prob;
    }

    /* * getter and setter methods	 *
     */
    public String getOutputFileName() {
        return outputFileName;
    }

    public void setOutputFileName(String outputFileName) {
        this.outputFileName = outputFileName;
    }

    public int getMaxPatchSize() {
        return maxPatchSize;
    }

    public void setMaxPatchSize(int maxPatchSize) {
        this.maxPatchSize = maxPatchSize;
    }

    public int getFileTotalReadings() {
        return fileTotalReadings;
    }

    public void setFileTotalReadings(int fileTotalReadings) {
        this.fileTotalReadings = fileTotalReadings;
    }

    public void setDataSeg(Segment dataSeg) {
        this.dataSeg = dataSeg;
    }

    public void setAckSeg(Segment ackSeg) {
        this.ackSeg = ackSeg;
    }

    public void setCurrRetry(int currRetry) {
        this.currRetry = currRetry;
    }

}


import java.net.DatagramSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Scanner;

public class Client {
	private static final byte readReq = 0x01;
	private static final byte writeReq = 0x02;
	private static final String mode = "octet";
	private static final String badTID = "Invalid TID";
	private static final String diskFull ="Disk full, cannot continue read";
	private byte[] rData;

	private DatagramSocket sock;
	private DatagramPacket sndPkt, rcvPkt;
	private InetAddress target;

	private int port, TID;
	private boolean test, verbose;

	public Client() throws UnknownHostException, SocketException {
		target = InetAddress.getLocalHost();

		test = false;
		verbose = false;

		sock = new DatagramSocket();

		new UI().start();
	}

	/**
	 * Prompts the user to enter the name of the file to be transferred.
	 * @return The name of the file to be transferred.
	 */
	private String pickFile() {
		String file;
		Scanner stream = new Scanner(System.in);
		System.out.println("Enter filename.");
		file = stream.nextLine();

		return file;
	}

	/**
	 * Send sndPkt from sock.
	 * <p>
	 * Note that this function should never be called until after createPkt has been called.
	 */
	private void send() {
		try {
			sock.send(sndPkt);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Receive from sock into rcvPkt.
	 */
	private void receive() {
		rData = new byte[516];
		rcvPkt = new DatagramPacket(rData, 516);

		try {
			sock.receive(rcvPkt);
			
			//Received error packet.
			if (rcvPkt.getData()[1]==5) {
				if (rcvPkt.getData()[3] == 4) {
					System.out.println("Illegal TFTP operation was requested.");
					quit();
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Closes the socket and exits.
	 */
	private void quit() {
		if (verbose) System.out.println("Closing sock");
		sock.close();
		System.out.println("Exiting");
		System.exit(0);
	}

	/**
	 * Generates an error message.
	 * <p>
	 * Builds an error message of the format:
	 * {0x00, 0x05, 0x00, type, errorMsg}
	 * @param type Which error condition is present.
	 * @param errorMsg A more detailed message describing the error. 
	 * @return A byte array containing the error.
	 */
	public byte[] createErrorMsg(byte type, byte[] errorMsg) {
		byte msg[] = new byte[errorMsg.length + 5];

		msg[0] = 0x00;
		msg[1] = 0x05;
		msg[2] = 0x00;
		msg[3] = type;

		System.arraycopy(errorMsg, 0, msg, 4, errorMsg.length);

		msg[msg.length - 1] = 0x00;

		return msg;
	}

	/**
	 * Starts a write operation. Reads from a local file and writes to the server across the network.
	 * @throws IOException
	 */
	private void startWrite() throws IOException {
		int sizeRead;

		String file = pickFile();

		// Holds the block number. Since this is a write operation, the lowest block number the client uses is 01.
		byte[] block = {0x00, 0x01};
		// Holds the opcode.  This never changes, and is here for convenience (can be copied in with arraycopy).
		byte[] opcode = {0x00, 0x03};
		byte[] request = buildRQ(file, writeReq);
		byte[] data = new byte[512];

		// Opens the file selected for reading.
		if (verbose) System.out.println("Opening file.");
		BufferedInputStream in = null;
		try{
		in = new BufferedInputStream(new FileInputStream("Client/" + file));
		}catch (FileNotFoundException e) 
		{
			System.out.println("File name " + file + " could not be found.");
			quit();
		}
		// Build the WRQ packet from the request array.
		if (verbose) System.out.println("Sending request.");
		sndPkt = new DatagramPacket(request, request.length, target, test ? 23 : 69);
		send();
		receive();
		
		// Set the destination port and address based on the request response.
		port = rcvPkt.getPort();
		target = rcvPkt.getAddress();
		TID = port;
		
		if (verbose) {
			System.out.print("Response received from ");
			System.out.print(target.getHostAddress());
			System.out.print(" on port ");
			System.out.println(port);
			System.out.print("Opcode ");
			System.out.println(new Integer(rcvPkt.getData()[1]));
			System.out.println(new String(rcvPkt.getData()));
			System.out.println();
		}
		if(rcvPkt.getData()[1] == 5){
		
		if(rcvPkt.getData()[3] == 6)
		{
			byte[] errorMsg = new byte[rcvPkt.getLength()];
			
			System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
			if (verbose) System.out.println("Error code 6, file exists on server");
			System.out.println("File already exists.");
			
			quit();
		}
		if(rcvPkt.getData()[3] == 2)
		{
			byte[] errorMsg = new byte[rcvPkt.getLength()];
			
			System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
			if (verbose) System.out.println("Error code 2, access denied.");
			System.out.println(errorMsg);
			quit();
		}
		}
		// Read in up to 512 bytes of data.
		sizeRead = in.read(data);

		if (verbose) System.out.println("Starting write.");

		/*
		 * While the end of the file hasn't been reached:
		 *   - Copy the opcode, block number, and data into a single array.
		 *   - Put the array into a datagram packet.
		 *   - Send the packet and wait for a response.
		 *   - Increment the block number.
		 *   - Read in a new set of data.
		 */
		while (sizeRead != -1) {
			request = new byte[4 + sizeRead];

			System.arraycopy(opcode, 0, request, 0, 2);
			System.arraycopy(block, 0, request, 2, 2);
			System.arraycopy(data, 0, request, 4, sizeRead);

			if (verbose) {
				System.out.print("Sending ");
				System.out.print(new String(request));
				System.out.print(" to ");
				System.out.println(port);
				System.out.print("Opcode ");
				System.out.println(new Integer(request[1]));
				System.out.println();
			}

			sndPkt = new DatagramPacket(request, request.length, target, port);

			send();
			receive();

			if (verbose) {
				System.out.println("Received packet");
				System.out.print("Opcode ");
				System.out.println(new Integer(rcvPkt.getData()[1]));
				System.out.println(new String(rcvPkt.getData()));
				System.out.println();
			}

			if(rcvPkt.getPort() != TID) {
				if (verbose) System.out.println(badTID);
				
				byte[] errorData =createErrorMsg((byte) 5, badTID.getBytes());
				sndPkt = new DatagramPacket (errorData, errorData.length, target, port);
				
				send();
				//receive?
			}
			
			if(rcvPkt.getData()[1]==5) {

				if (rcvPkt.getData()[3]==5) {
					System.out.println("Data sent to incorrect server, attempting to retransfer");
					
					if (verbose) {
						System.out.print("Sending ");
						System.out.print(new String(request));
						System.out.print(" to ");
						System.out.println(port);
						System.out.print("Opcode ");
						System.out.println(new Integer(request[1]));
						System.out.println();
					}

					send();
					receive(); 
					if (verbose) {
						System.out.println("Received packet");
						System.out.print("Opcode ");
						System.out.println(new Integer(rcvPkt.getData()[1]));
						System.out.println(new String(rcvPkt.getData()));
						System.out.println();
					}
				}
					
					 if (rcvPkt.getData()[3] == 0x04) {
						// Invalid TFTP operation. Unrecoverable by definition.
						byte[] errorMsg = new byte[rcvPkt.getLength()];
						
						System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
						
						if (verbose) System.out.println("Error code 4, Invalid TFTP Operation");
						System.out.println(new String(errorMsg));
						
						quit();
					}
					 System.out.println(rcvPkt.getData()[3]);
					 
					 if(rcvPkt.getData()[3] == 1)
					{
						byte[] errorMsg = new byte[rcvPkt.getLength()];
						
						System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
						if (verbose) System.out.println("Error code 1, file not found");
						System.out.println(errorMsg);
						quit();
					}
					 if(rcvPkt.getData()[3] == 3)
						{
							byte[] errorMsg = new byte[rcvPkt.getLength()];
							
							System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
							if (verbose) System.out.println("Error code 3, disk full");
							System.out.println(errorMsg);
							quit();
						}
					
				}
			

			if (++block[1] == 0) block[0]++;

			sizeRead = in.read(data);
		}

		in.close();
		System.out.println("Finished write.");
	}

	/**
	 * Starts a read operation. Reads from the server and writes to a local file.
	 * @throws IOException
	 */
	private void startRead() throws IOException {
		byte[] data;

		Boolean first = true;

		// Prompt the user to select a file to read, then open and/or create the file to write to.
		String filename = pickFile();
		if (verbose) System.out.println("Opening file.");
		File readFile= new File("Client/" +filename);
		
		BufferedOutputStream out=null;
	
		if (readFile.exists()){
			System.out.println("File already exists");
			if (verbose){System.out.println("File already exists in Client");}
			quit();
		}else{
		
			try{
			out = new BufferedOutputStream(new FileOutputStream("Client/" + filename));
			}catch (FileNotFoundException e) 
			{
				System.out.println("Folder access denied.");
				quit();
			}
		 
		}
		// Build the data buffer for the RRQ.
		byte[] request = buildRQ(filename, readReq);

		// Hold the block number and opcode.  The block number starts at zero to simplify the increment logic.
		byte[] block = {0x00, 0x00};
		byte[] opcode = {0x00, 0x04};

		// Build the RRQ packet from the request array, send the request, then wait for a response.
		if (verbose) System.out.println("Sending request.");
		sndPkt = new DatagramPacket(request, request.length, target, test ? 23 : 69);
		send();
		receive();

		// Set the destination port and address based on the request response.
		target = rcvPkt.getAddress();
		port = rcvPkt.getPort();
		TID = port;

		if (verbose) {
			System.out.print("Response received from ");
			System.out.print(target.getHostAddress());
			System.out.print(" on port ");
			System.out.println(port);
			System.out.println("Starting read.");
		}

		/*
		 * While the packet received is 516 bytes (4 byte header plus 512 bytes data):
		 *   - Receive a packet.
		 *   - Increment the block number.
		 *   - Separate the data from the header.
		 *   - Write the data to the file.
		 *   - Send the response.
		 */
		do {
			// Used to prevent a double receive() on the first block. 
			if (first) {
				first = false;
			} else receive();

			if (verbose) {
				System.out.println("Received packet");
				System.out.print("Opcode ");
				System.out.println(new Integer(rcvPkt.getData()[1]));
				System.out.println(new String(rcvPkt.getData()));
			}

			if(rcvPkt.getPort() != TID) {
				if (verbose) System.out.println(badTID);
				byte [] errorData = createErrorMsg((byte) 5, badTID.getBytes());
				sndPkt = new DatagramPacket (errorData, errorData.length, target, port);
				
				send();
				//change so it received?
				
			}

			if(rcvPkt.getData()[1]==5) {
				if (rcvPkt.getData()[3]==5) {
					System.out.println("Acknowledge went to wrong server, attempting to retransfer");

					send();
					receive();
				
					if (verbose) {
						System.out.println("Received packet");
						System.out.print("Opcode ");
						System.out.println(new Integer(rcvPkt.getData()[1]));
						System.out.println(new String(rcvPkt.getData()));
					}
				}
				if (rcvPkt.getData()[3] == 0x04) {
					// Invalid TFTP operation. Unrecoverable by definition.
					byte[] errorMsg = new byte[rcvPkt.getLength()];
					
					System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
					
					if (verbose) System.out.println("Error code 4, Invalid TFTP Operation");
					
					System.out.println(new String(errorMsg));
					
					quit();
				}
				if(rcvPkt.getData()[3] == 2)
				{
					byte[] errorMsg = new byte[rcvPkt.getLength()];
					
					System.arraycopy(rcvPkt.getData(), 4, errorMsg, 0, rcvPkt.getLength() - 5);
					if (verbose) System.out.println("Error code 1, file not found");
					System.out.println("Could not find specified file on server.");
					
					quit();
				}
				
			}

			if (++block[1] == 0) block[0]++;

			data = new byte[rcvPkt.getLength() - 4];
			System.arraycopy(rcvPkt.getData(), 4, data, 0, data.length);
			try{
				out.write(data, 0, data.length);
				out.flush();
			}
			catch (IOException e1) {
				
				System.out.println(diskFull);
				if (verbose){System.out.println("Error code 3, sending to server");}
				
				
				byte[] errorData =createErrorMsg((byte) 3, diskFull.getBytes());
				sndPkt = new DatagramPacket (errorData, errorData.length, target, port);
				
				send();
				quit();
			}

			request = new byte[4];
			System.arraycopy(opcode, 0, request, 0, 2);
			System.arraycopy(block, 0, request, 2, 2);

			sndPkt = new DatagramPacket(request, request.length, target, port);

			send();
		} while (rcvPkt.getLength() > 515);
		
		out.close();
		System.out.println("Finished read.");
	}

	/**
	 * Builds a byte array for a request packet.
	 * @param file The name of the file to be read or written.
	 * @param opcode The opcode indicating whether it is a read or write request.
	 * @return The data buffer for the request packet.
	 */
	private byte[] buildRQ(String file, byte opcode) {
		byte[] request;
		byte[] code = {0x00, opcode};
		request = new byte[file.length() + mode.length() + 4];

		System.arraycopy(code, 0, request, 0, 2);		
		System.arraycopy(file.getBytes(), 0, request, 2, file.length());
		request[file.length() + 2] = 0x00;

		System.arraycopy(mode.getBytes(), 0, request, file.length() + 3, mode.length());
		request[request.length - 1] = 0x00;

		return request;
	}

	private void setTarget() {
		String ip;
		Scanner stream = new Scanner(System.in);
		System.out.println("Enter the target IP address: ");
		ip = stream.nextLine();

		try {
			target = InetAddress.getByName(ip);
		} catch (UnknownHostException e) {
			System.out.println("Invalid IP.");
		}
	}

	/**
	 * UI
	 * @author MatthewPenner
	 * The UI class handles user inputs. It allows the user input to be read during transfers.
	 */
	private class UI extends Thread {
		private Boolean quit;

		public UI () {
			quit = false;
		}

		/**
		 * Prints out the user's options.
		 */
		private void printUI() {
			System.out.println("T - Toggle test mode");
			System.out.println("V - Toggle verbose mode");
			System.out.println("W - Initiate file write");
			System.out.println("R - Initiate file read");
			System.out.println("I - Set the target IP (Default localhost)");
			System.out.println("Q - Quit");
			System.out.print("Test: "); System.out.print(test); System.out.print("    Verbose: "); System.out.println(verbose);
		}

		/**
		 * Prints the options and receives the user's inputs.
		 * @throws IOException
		 */
		public void ui() throws IOException {
			String command;
			Scanner input = new Scanner(System.in);

			while (!quit) {
				printUI();
				command = input.nextLine();

				switch (command.toLowerCase().charAt(0)) {
					case 'q': quit = true;
							  quit();
							  break;
					case 't': test = !test;
							  break;
					case 'v': verbose = !verbose;
							  break;
					case 'w': startWrite();
							  break;
					case 'r': startRead();
							  break;
					case 'i': setTarget();
							  break;
				}
			}
		}

		public void run() {
			try {
				this.ui();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) throws IOException {
		Client client = new Client();
	}

}

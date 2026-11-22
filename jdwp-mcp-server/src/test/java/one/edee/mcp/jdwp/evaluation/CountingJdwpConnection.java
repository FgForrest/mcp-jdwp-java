package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.connect.spi.Connection;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A loopback JDWP transport for the live test, using the public JDI Connection SPI. */
final class CountingJdwpConnection extends Connection implements AutoCloseable {
    private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 11;
    private static final int REPLY = 0x80;

    record Command(int set, int command) {
    }

    private final Socket socket;
    private final DataInputStream input;
    private final Map<Integer, Command> pending = new ConcurrentHashMap<>();
    private final Map<Command, Long> completed = new HashMap<>();

    static CountingJdwpConnection attach(int port) throws IOException {
        final Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(HANDSHAKE);
            final var input = new DataInputStream(socket.getInputStream());
            if (!Arrays.equals(HANDSHAKE, input.readNBytes(HANDSHAKE.length))) {
                throw new IOException("Target did not complete the JDWP handshake");
            }
            socket.setSoTimeout(0);
            return new CountingJdwpConnection(socket, input);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private CountingJdwpConnection(Socket socket, DataInputStream input) {
        this.socket = socket;
        this.input = input;
    }

    @Override
    public byte[] readPacket() throws IOException {
        final int length;
        try {
            length = input.readInt();
        } catch (EOFException e) {
            return new byte[0]; // The JDI SPI represents a closed input stream as an empty packet.
        }
        if (length < HEADER_SIZE) {
            throw new IOException("Invalid JDWP packet length " + length);
        }
        final byte[] packet = new byte[length];
        ByteBuffer.wrap(packet).putInt(length);
        input.readFully(packet, Integer.BYTES, length - Integer.BYTES);
        if ((packet[8] & REPLY) != 0) {
            final Command command = pending.remove(id(packet));
            if (command != null) {
                // Count before returning the reply to JDI, which wakes the waiting caller.
                synchronized (completed) {
                    completed.merge(command, 1L, Long::sum);
                }
            }
        }
        return packet;
    }

    @Override
    public synchronized void writePacket(byte[] packet) throws IOException {
        if ((packet[8] & REPLY) == 0) {
            pending.put(id(packet), new Command(Byte.toUnsignedInt(packet[9]), Byte.toUnsignedInt(packet[10])));
        }
        socket.getOutputStream().write(packet);
    }

    Map<Command, Long> snapshot() {
        synchronized (completed) {
            return Map.copyOf(completed);
        }
    }

    private static int id(byte[] packet) {
        return ByteBuffer.wrap(packet).getInt(Integer.BYTES);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    @Override
    public boolean isOpen() {
        return !socket.isClosed();
    }
}

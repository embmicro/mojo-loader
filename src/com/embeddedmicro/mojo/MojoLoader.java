package com.embeddedmicro.mojo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.eclipse.swt.widgets.Display;

import jssc.SerialPort;
import jssc.SerialPortException;
import jssc.SerialPortList;
import jssc.SerialPortTimeoutException;

public class MojoLoader {
	private Display display;
	private TextProgressBar bar;
	private SerialPort serialPort;
	private Callback callback;
	private boolean terminal;
	private Thread thread;

	public MojoLoader(Display display, TextProgressBar bar, Callback callback,
			boolean terminal) {
		this.display = display;
		this.bar = bar;
		this.callback = callback;
		this.terminal = terminal;
	}

	private void updateProgress(final float value) {
		if (!terminal) {
			display.asyncExec(new Runnable() {
				public void run() {
					if (bar.isDisposed())
						return;
					bar.setSelection((int) (value * 100.0f));
				}
			});
		} else {
			System.out.print("\r\33[20C"
					+ String.format("%-4s", (int) (value * 100.0f) + "%"));
		}
	}

	private void updateText(final String text) {
		if (!terminal) {
			display.asyncExec(new Runnable() {
				public void run() {
					if (bar.isDisposed())
						return;
					bar.setText(text);
				}
			});
		} else {
			System.out.print("\n" + String.format("%-20s", text));
		}
	}

	private void restartMojo() throws InterruptedException, SerialPortException {
		serialPort.setDTR(false);
		Thread.sleep(5);
		for (int i = 0; i < 5; i++) {
			serialPort.setDTR(false);
			Thread.sleep(5);
			serialPort.setDTR(true);
			Thread.sleep(5);
		}
	}
	
	public void clearFlash(final String port) {
		thread = new Thread() {
			public void run() {
				updateText("Connecting...");
				if (!terminal)
					updateProgress(0.0f);
				try {
					connect(port);
				} catch (Exception e) {
					onError("Could not connect to port " + port + "!");
					return;
				}

				try {
					restartMojo();
				} catch (InterruptedException | SerialPortException e) {
					onError(e.getMessage());
					return;
				}

				try {
					updateText("Erasing...");

					serialPort.readBytes(); // flush the buffer

					serialPort.writeByte((byte) 'E'); // Erase flash

					if (serialPort.readBytes(1, 10000)[0] != 'D') {
						onError("Mojo did not acknowledge flash erase!");
						return;
					}

					updateText("Done");
					updateProgress(1.0f);

				} catch (SerialPortException | SerialPortTimeoutException e) {
					onError(e.getMessage());
					return;
				}

				try {
					serialPort.closePort();
				} catch (SerialPortException e) {
					onError(e.getMessage());
					return;
				}
				if (callback != null)
					callback.onSuccess();
			}
		};
		thread.start();
	}
/*
	public void clearFlash(final String port) {
		new Thread() {
			public void run() {
				updateText("Connecting...");
				updateProgress(0.0f);
				try {
					connect(port);
				} catch (Exception e) {
					onError("Could not connect to port " + port + "!");
					return;
				}

				try {
					restartMojo();
				} catch (InterruptedException e) {
					onError(e.getMessage());
					return;
				} catch (SerialPortException e) {
					onError(e.getMessage());
				}

				try {
					updateText("Erasing...");

					while (in.available() > 0)
						in.skip(in.available()); // Flush the buffer

					out.write('E'); // Erase flash

					if (read(TIMEOUT) != 'D') {
						onError("Mojo did not acknowledge flash erase!");
						return;
					}

					updateText("Done");
					updateProgress(1.0f);

				} catch (IOException | TimeoutException e) {
					onError(e.getMessage());
					return;
				}

				try {
					in.close();
					out.close();
				} catch (IOException e) {
					onError(e.getMessage());
					return;
				}

				try {
					serialPort.closePort();
				} catch (SerialPortException e) {
					onError(e.getMessage());
				}
				if (callback != null)
					callback.onSuccess();
			}
		}.start();
	}

	public void sendBin(final String port, final String binFile,
			final boolean flash, final boolean verify) {
		new Thread() {
			public void run() {
				updateText("Connecting...");
				if (!terminal)
					updateProgress(0.0f);
				try {
					connect(port);
				} catch (Exception e) {
					onError("Could not connect to port " + port + "!");
					return;
				}

				File file = new File(binFile);
				InputStream bin = null;
				try {
					bin = new BufferedInputStream(new FileInputStream(file));
				} catch (FileNotFoundException e) {
					onError("The bin file could not be opened!");
					return;
				}

				try {
					restartMojo();
				} catch (InterruptedException | SerialPortException e) {
					onError(e.getMessage());
					try {
						bin.close();
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					return;
				}

				try {
					while (in.available() > 0)
						in.skip(in.available()); // Flush the buffer

					

					if (flash) {
						updateText("Erasing...");
						if (verify)
							out.write('V'); // Write to flash
						else
							out.write('F');
					} else {
						updateText("Loading...");
						out.write('R'); // Write to FPGA
					}

					if (read(TIMEOUT) != 'R') {
						onError("Mojo did not respond! Make sure the port is correct.");
						bin.close();
						return;
					}

					int length = (int) file.length();

					byte[] buff = new byte[4];

					for (int i = 0; i < 4; i++) {
						buff[i] = (byte) (length >> (i * 8) & 0xff);
					}

					out.write(buff);

					if (read(TIMEOUT) != 'O') {
						onError("Mojo did not acknowledge transfer size!");
						bin.close();
						return;
					}
					
					updateText("Loading...");

					updateProgress(1.0f);

					int num;
					int count = 0;
					int oldCount = 0;
					int percent = length / 100;
					byte[] data = new byte[percent];
					while (true) {
						int avail = bin.available();
						avail = avail > percent ? percent : avail;
						if (avail == 0)
							break;
						int read = bin.read(data, 0, avail);
						out.write(data, 0, read);
						count += read;

						if (count - oldCount > percent) {
							oldCount = count;
							float prog = (float) count / length;
							updateProgress(prog);
						}
					}

					if (read(TIMEOUT) != 'D') {
						onError("Mojo did not acknowledge the transfer!");
						bin.close();
						return;
					}

					bin.close();

					if (flash && verify) {
						updateText("Verifying...");
						bin = new BufferedInputStream(new FileInputStream(file));
						out.write('S');

						int size = (int) (file.length() + 5);

						int tmp;
						if ((tmp = read(TIMEOUT)) != 0xAA) {
							onError("Flash does not contain valid start byte! Got: "
									+ tmp);
							bin.close();
							return;
						}

						int flashSize = 0;
						for (int i = 0; i < 4; i++) {
							flashSize |= read(TIMEOUT) << (i * 8);
						}

						if (flashSize != size) {
							onError("File size mismatch!\nExpected " + size
									+ " and got " + flashSize);
							bin.close();
							return;
						}

						count = 0;
						oldCount = 0;
						while ((num = bin.read()) != -1) {
							int d = read(TIMEOUT);
							if (d != num) {
								onError("Verification failed at byte " + count
										+ " out of " + length + "\nExpected "
										+ num + " got " + d);
								bin.close();
								return;
							}
							count++;
							if (count - oldCount > percent) {
								oldCount = count;
								float prog = (float) count / length;
								updateProgress(prog);
							}
						}
					}

					if (flash) {
						out.write('L');
						if (read(TIMEOUT) != 'D') {
							onError("Could not load from flash!");
							bin.close();
							return;
						}
					}

					bin.close();
				} catch (IOException | TimeoutException e) {
					onError(e.getMessage());
					return;
				}

				updateProgress(1.0f);
				updateText("Done");

				try {
					in.close();
					out.close();
				} catch (IOException e) {
					onError(e.getMessage());
					return;
				}

				try {
					serialPort.closePort();
				} catch (SerialPortException e) {
					onError(e.getMessage());
				}
				if (callback != null)
					callback.onSuccess();
				if (terminal)
					System.out.print("\n");
			}
		}.start();
	}
	*/
	
	public void sendBin(final String port, final String binFile, final boolean flash, final boolean verify) {
		thread = new Thread() {
			public void run() {
				updateText("Connecting...");
				if (!terminal)
					updateProgress(0.0f);
				try {
					connect(port);
				} catch (Exception e) {
					onError("Could not connect to port " + port + "!");
					return;
				}

				File file = new File(binFile);
				InputStream bin = null;
				try {
					bin = new BufferedInputStream(new FileInputStream(file));
				} catch (FileNotFoundException e) {
					onError("The bin file could not be opened!");
					return;
				}

				try {
					restartMojo();
				} catch (InterruptedException | SerialPortException e) {
					onError(e.getMessage());
					try {
						bin.close();
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					return;
				}

				try {
					serialPort.readBytes(); // flush the buffer

					if (flash)
						updateText("Erasing flash...");
					else
						updateText("Loading to RAM...");

					if (flash) {
						if (verify)
							serialPort.writeByte((byte) 'V'); // Write to flash
						else
							serialPort.writeByte((byte) 'F');
					} else {
						serialPort.writeByte((byte) 'R'); // Write to FPGA
					}

					if (serialPort.readBytes(1, 2000)[0] != 'R') {
						onError("Mojo did not respond! Make sure the port is correct.");
						bin.close();
						return;
					}

					int length = (int) file.length();

					byte[] buff = new byte[4];

					for (int i = 0; i < 4; i++) {
						buff[i] = (byte) (length >> (i * 8) & 0xff);
					}

					serialPort.writeBytes(buff);

					if (serialPort.readBytes(1, 10000)[0] != 'O') {
						onError("Mojo did not acknowledge transfer size!");
						bin.close();
						return;
					}
					
					if (flash)
						updateText("Loading to flash...");

					int num;
					int count = 0;
					int oldCount = 0;
					int percent = length / 100;
					byte[] data = new byte[percent];
					while (true) {
						int avail = bin.available();
						avail = avail > percent ? percent : avail;
						if (avail == 0)
							break;
						int read = bin.read(data, 0, avail);
						serialPort.writeBytes(Arrays.copyOf(data, read));
						count += read;

						if (count - oldCount > percent) {
							oldCount = count;
							float prog = (float) count / length;
							updateProgress(prog);
						}
					}

					updateProgress(1.0f);

					if (serialPort.readBytes(1, 2000)[0] != 'D') {
						onError("Mojo did not acknowledge the transfer!");
						bin.close();
						return;
					}

					bin.close();

					if (flash && verify) {
						updateText("Verifying...");
						bin = new BufferedInputStream(new FileInputStream(file));
						serialPort.writeByte((byte) 'S');

						int size = (int) (file.length() + 5);

						int tmp;
						if (((tmp = serialPort.readBytes(1, 2000)[0]) & 0xff) != 0xAA) {
							onError("Flash does not contain valid start byte! Got: " + tmp);
							bin.close();
							return;
						}

						int flashSize = 0;
						for (int i = 0; i < 4; i++) {
							flashSize |= (((int) serialPort.readBytes(1, 2000)[0]) & 0xff) << (i * 8);
						}

						if (flashSize != size) {
							onError("File size mismatch!\nExpected " + size + " and got " + flashSize);
							bin.close();
							return;
						}

						count = 0;
						oldCount = 0;
						while ((num = bin.read()) != -1) {
							int d = (((int) serialPort.readBytes(1, 2000)[0]) & 0xff);
							if (d != num) {
								onError("Verification failed at byte " + count + " out of " + length + "\nExpected " + num + " got " + d);
								bin.close();
								return;
							}
							count++;
							if (count - oldCount > percent) {
								oldCount = count;
								float prog = (float) count / length;
								updateProgress(prog);
							}
						}
						updateProgress(1.0f);
					}

					if (flash) {
						serialPort.writeByte((byte) 'L');
						if ((((int) serialPort.readBytes(1, 5000)[0]) & 0xff) != 'D') {
							onError("Could not load from flash!");
							bin.close();
							return;
						}
					}

					bin.close();
				} catch (IOException | SerialPortException | SerialPortTimeoutException e) {
					onError(e.getMessage());
					return;
				}

				updateText("Done");

				try {
					serialPort.closePort();
				} catch (SerialPortException e) {
					onError(e.getMessage());
					return;
				}
				
				if (callback != null)
					callback.onSuccess();
			}
		};
		thread.start();
	}

	private void onError(String e) {
		if (e == null)
			e = "";
		if (callback != null)
			callback.onError(e);
		updateProgress(0.0f);
		updateText("");

		if (serialPort != null && serialPort.isOpened())
			try {
				serialPort.closePort();
			} catch (SerialPortException e1) {
				e1.printStackTrace();
			}
	}

	private void connect(String portName) throws Exception {
		if (portName.equals(""))
			throw new Exception("A serial port must be selected!");
		if (!Arrays.asList(SerialPortList.getPortNames()).contains(portName)) {
			throw new Exception("Port " + portName + " could not be found. Please select a different port.");

		}

		serialPort = new SerialPort(portName);
		serialPort.openPort();
		serialPort.setParams(115200, 8, 1, 0);
	}

}

/* Copyright (C) 2015 American Printing House for the Blind Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.aph.braillezephyr;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.ExtendedModifyEvent;
import org.eclipse.swt.custom.ExtendedModifyListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * This class is the container for both the styleviews that display braille
 * and ascii.
 * </p>
 *
 * @author Mike Gray mgray@aph.org
 */
public class BZStyledText
{
	private final static char PARAGRAPH_END = 0xfeff;

	private final Shell parentShell;
	private final Composite composite;
	private final StyledText brailleText, asciiText;
	private final StyledTextContent content;

	private final Color color;
	private final boolean windowBug = System.getProperty("os.name").toLowerCase().startsWith("windows");

	private StyledText currentText;

	private String eol = System.getProperty("line.separator");
	private int linesPerPage = 25;
	private int charsPerLine = 40;
	private int bellLineMargin = 33;
	private Clip clipMarginBell;
	private int bellPageMargin = 25;
	private Clip clipPageBell;

	private final List<ExtendedModifyEvent> changes = new ArrayList<>();
	private int changeIndex, saveIndex;
	private boolean undoing, redoing;

	/**
	 * <p>
	 * Creates a new <code>BZStyledText</code> for parentShell <code>parentShell</code>.
	 * </p>
	 *
	 * @param parent parentShell of the new instance (cannot be null)
	 */
	public BZStyledText(Shell parent)
	{
		this.parentShell = parent;

		color = parent.getDisplay().getSystemColor(SWT.COLOR_BLACK);

		composite = new Composite(parent, 0);
		composite.setLayout(new GridLayout(2, true));

		//   load LouisBraille-Regular.otf font
		try
		{
			InputStream fontInputStream = getClass().getResourceAsStream("/fonts/LouisBraille-Regular.otf");
			//File fontFile = File.createTempFile("BrailleZephyr-font-", ".otf");
			File fontFile = new File(System.getProperty("java.io.tmpdir") + File.separator + "BrailleZephyr-font.otf");
			FileOutputStream fontOutputStream = new FileOutputStream(fontFile);
			byte buffer[] = new byte[27720];
			int length;
			while((length = fontInputStream.read(buffer)) > 0)
				fontOutputStream.write(buffer, 0, length);
			fontInputStream.close();
			fontOutputStream.close();

			parent.getDisplay().loadFont(fontFile.getPath());
			//if(parentShell.getDisplay().getFontList("LouisBraille", true).length == 0)
		}
		catch(IOException ignored){}

		//   load LouisBraille-Regular.ttf font
		try
		{
			InputStream fontInputStream = getClass().getResourceAsStream("/fonts/LouisBraille-Regular.ttf");
			//File fontFile = File.createTempFile("BrailleZephyr-font-", ".ttf");
			File fontFile = new File(System.getProperty("java.io.tmpdir") + File.separator + "BrailleZephyr-font.ttf");
			FileOutputStream fontOutputStream = new FileOutputStream(fontFile);
			byte buffer[] = new byte[27720];
			int length;
			while((length = fontInputStream.read(buffer)) > 0)
				fontOutputStream.write(buffer, 0, length);
			fontInputStream.close();
			fontOutputStream.close();

			parent.getDisplay().loadFont(fontFile.getPath());
			//if(parentShell.getDisplay().getFontList("LouisBraille", true).length == 0)
		}
		catch(IOException ignored){}

		//   load margin bell
		try
		{
			InputStream inputStreamBellMargin = new BufferedInputStream(getClass().getResourceAsStream("/sounds/margin_bell.wav"));
			AudioInputStream audioInputStreamMargin = AudioSystem.getAudioInputStream(inputStreamBellMargin);
			DataLine.Info dataLineInfoMargin = new DataLine.Info(Clip.class, audioInputStreamMargin.getFormat());
			clipMarginBell = (Clip)AudioSystem.getLine(dataLineInfoMargin);
			clipMarginBell.open(audioInputStreamMargin);
		}
		catch(UnsupportedAudioFileException ignored)
		{
			MessageBox messageBox = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
			messageBox.setMessage("Sound file unsupported for margin bell");
			messageBox.open();
			clipMarginBell = null;
		}
		catch(LineUnavailableException ignored)
		{
			MessageBox messageBox = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
			messageBox.setMessage("Line unavailable for margin bell");
			messageBox.open();
			clipMarginBell = null;
		}
		catch(IOException ignored)
		{
			MessageBox messageBox = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
			messageBox.setMessage("Error creating margin bell");
			messageBox.open();
			clipMarginBell = null;
		}

		//   load page bell
		try
		{
			InputStream inputStreamBellPage = new BufferedInputStream(getClass().getResourceAsStream("/sounds/page_bell.wav"));
			AudioInputStream audioInputStreamPage = AudioSystem.getAudioInputStream(inputStreamBellPage);
			DataLine.Info dataLineInfoPage = new DataLine.Info(Clip.class, audioInputStreamPage.getFormat());
			clipPageBell = (Clip)AudioSystem.getLine(dataLineInfoPage);
			clipPageBell.open(audioInputStreamPage);
		}
		catch(UnsupportedAudioFileException ignored)
		{
			MessageBox messageBox = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
			messageBox.setMessage("Sound file unsupported for page bell");
			messageBox.open();
			clipPageBell = null;
		}
		catch(LineUnavailableException ignored)
		{
			MessageBox messageBox = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
			messageBox.setMessage("Line unavailable for page bell");
			messageBox.open();
			clipPageBell = null;
		}
		catch(IOException ignored)
		{
			MessageBox messageBox = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
			messageBox.setMessage("Error creating page bell");
			messageBox.open();
			clipPageBell = null;
		}

		brailleText = new StyledText(composite, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
		brailleText.setLayoutData(new GridData(GridData.FILL_BOTH));
		brailleText.setFont(new Font(parent.getDisplay(), "LouisBraille", 18, SWT.NORMAL));
		brailleText.addFocusListener(new FocusHandler(brailleText));
		brailleText.addPaintListener(new PaintHandler(brailleText));
		BrailleKeyHandler brailleKeyHandler = new BrailleKeyHandler(true);
		brailleText.addKeyListener(brailleKeyHandler);
		brailleText.addVerifyKeyListener(brailleKeyHandler);
		brailleText.addExtendedModifyListener(new ExtendedModifyHandler(brailleText));

		content = brailleText.getContent();

		asciiText = new StyledText(composite, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
		asciiText.setContent(content);
		asciiText.setLayoutData(new GridData(GridData.FILL_BOTH));
		asciiText.setFont(new Font(parent.getDisplay(), "Courier", 18, SWT.NORMAL));
		asciiText.addFocusListener(new FocusHandler(asciiText));
		asciiText.addPaintListener(new PaintHandler(asciiText));
		asciiText.addVerifyKeyListener(new BrailleKeyHandler(false));
		asciiText.addExtendedModifyListener(new ExtendedModifyHandler(asciiText));

		brailleText.addCaretListener(new CaretHandler(brailleText, asciiText));
		asciiText.addCaretListener(new CaretHandler(asciiText, brailleText));

		currentText = brailleText;
	}

	Shell getParentShell()
	{
		return parentShell;
	}

	/**
	 * <p>
	 * Returns the current number of lines per page.
	 * </p>
	 *
	 * @return the current value
	 *
	 * @see #setLinesPerPage(int)
	 */
	public int getLinesPerPage()
	{
		return linesPerPage;
	}

	/**
	 * <p>
	 * Sets the current number of lines per page.
	 * </p><p>
	 * This also resets the page bell relative to the previous settings.
	 * </p>
	 *
	 * @param linesPerPage the new value
	 *
	 * @see #getLinesPerPage()
	 */
	public void setLinesPerPage(int linesPerPage)
	{
		int bellDiff = this.linesPerPage - bellPageMargin;
		this.linesPerPage = linesPerPage;
		bellPageMargin = linesPerPage - bellDiff;
		if(bellPageMargin < 0)
			bellPageMargin = 0;
	}

	/**
	 * <p>
	 * Returns the current number of characters per line.
	 * </p>
	 *
	 * @return the current value
	 *
	 * @see #setCharsPerLine(int)
	 */
	public int getCharsPerLine()
	{
		return charsPerLine;
	}

	/**
	 * <p>
	 * Sets the current number of characters per line.
	 * </p><p>
	 * This also resets the line bell relative to the previous settings.
	 * </p><p>
	 * The current lines are not reformatted.
	 * </p>
	 *
	 * @param charsPerLine the new value.
	 *
	 * @see #getCharsPerLine()
	 */
	public void setCharsPerLine(int charsPerLine)
	{
		int bellDiff = this.charsPerLine - bellLineMargin;
		this.charsPerLine = charsPerLine;
		bellLineMargin = charsPerLine - bellDiff;
		if(bellLineMargin < 0)
			bellLineMargin = 0;
	}

	/**
	 * <p>
	 * Returns the margin which the line bell is played.
	 * </p>
	 *
	 * @return the current value, -1 if no margin bell
	 *
	 * @see #setBellLineMargin(int)
	 */
	public int getBellLineMargin()
	{
		if(clipMarginBell == null)
			return -1;
		return bellLineMargin;
	}

	/**
	 * <p>
	 * Sets the current number of lines per page.
	 * </p><p>
	 * The sound is only played when the caret moves from one space before
	 * the margin to the margin.
	 * </p>
	 *
	 * @param bellLineMargin the new value
	 *
	 * @see #getBellLineMargin()
	 */
	public void setBellLineMargin(int bellLineMargin)
	{
		if(clipMarginBell == null)
			return;
		this.bellLineMargin = bellLineMargin;
	}

	/**
	 * <p>
	 * Returns the current number of characters per line.
	 * </p>
	 *
	 * @return the current value, -1 if no page bell
	 *
	 * @see #setBellPageMargin(int)
	 */
	public int getBellPageMargin()
	{
		if(clipPageBell == null)
			return -1;
		return bellPageMargin;
	}

	/**
	 * <p>
	 * Sets the current number of lines per page.
	 * </p><p>
	 * The sound is only played when the caret moves from the line before
	 * the margin to the margin when the enter key is pressed.
	 * </p>
	 *
	 * @param bellPageMargin the new value
	 *
	 * @see #getBellPageMargin()
	 */
	public void setBellPageMargin(int bellPageMargin)
	{
		if(clipPageBell == null)
			return;
		this.bellPageMargin = bellPageMargin;
	}

	/**
	 * <p>
	 * Returns if the braille text is visible.
	 * </p>
	 *
	 * @return the current visibility of the braille text
	 *
	 * @see #setBrailleVisible(boolean)
	 */
	public boolean getBrailleVisible()
	{
		return brailleText.getVisible();
	}

	/**
	 * <p>
	 * Sets the visibility of the braille text.
	 * </p>
	 *
	 * @param visible the visibility of the braille text
	 *
	 * @see #getBrailleVisible()
	 */
	public void setBrailleVisible(boolean visible)
	{
		((GridData)brailleText.getLayoutData()).exclude = !visible;
		brailleText.setVisible(visible);
		((GridLayout)composite.getLayout()).makeColumnsEqualWidth = visible && asciiText.getVisible();
		composite.layout();
	}

	/**
	 * <p>
	 * Returns if the ascii text is visible.
	 * </p>
	 *
	 * @return the current visibility of the ascii text
	 *
	 * @see #setAsciiVisible(boolean)
	 */
	public boolean getAsciiVisible()
	{
		return asciiText.getVisible();
	}

	/**
	 * <p>
	 * Sets the visibility of the ascii text.
	 * </p>
	 *
	 * @param visible the visibility of the ascii text
	 *
	 * @see #getAsciiVisible()
	 */
	public void setAsciiVisible(boolean visible)
	{
		((GridData)asciiText.getLayoutData()).exclude = !visible;
		asciiText.setVisible(visible);
		((GridLayout)composite.getLayout()).makeColumnsEqualWidth = visible && brailleText.getVisible();
		composite.layout();
	}

	/**
	 * <p>
	 * Returns the current font of the braille text.
	 * </p>
	 *
	 * @return the current font
	 *
	 * @see #setBrailleFont(Font)
	 */
	public Font getBrailleFont()
	{
		return brailleText.getFont();
	}

	/**
	 * <p>
	 * Sets the font for the braille text.
	 * </p>
	 *
	 * @param font the new font
	 *
	 * @see #getAsciiVisible()
	 */
	public void setBrailleFont(Font font)
	{
		brailleText.setFont(font);
	}

	/**
	 * <p>
	 * Returns the current font of the ascii text.
	 * </p>
	 *
	 * @return the current font
	 *
	 * @see #setAsciiFont(Font)
	 */
	public Font getAsciiFont()
	{
		return asciiText.getFont();
	}

	/**
	 * <p>
	 * Sets the font for the ascii text.
	 * </p>
	 *
	 * @param font the new font
	 *
	 * @see #getAsciiFont()
	 */
	public void setAsciiFont(Font font)
	{
		asciiText.setFont(font);
	}

	//TODO:  getText()

	/**
	 * <p>
	 * Sets the text for both braille and ascii texts.
	 * </p>
	 *
	 * @param text the string to set the text
	 */
	public void setText(String text)
	{
		content.setText(text);
		changes.clear();
		changeIndex = saveIndex = 0;
	}

	/**
	 * <p>
	 * Redraw both braille and ascii texts.
	 * </p>
	 */
	public void redraw()
	{
		//TODO:  both?
		brailleText.redraw();
		asciiText.redraw();
	}

	private void scrollToCaret()
	{
		int caretOffset = currentText.getCaretOffset();
		int lineIndex = currentText.getLineAtOffset(caretOffset);
		int lineHeight = currentText.getLineHeight();
		int linesVisible = currentText.getSize().y / lineHeight;
		int lineMiddle = linesVisible / 2;
		int lineTop = lineIndex - lineMiddle;
		if(lineTop < 0)
			lineTop = 0;
		currentText.setTopIndex(lineTop);
	}

	private void clearChanges()
	{
		changes.clear();
		changeIndex = saveIndex = 0;
	}

	private void resetChanges()
	{
		saveIndex = changeIndex;
	}

	/**
	 * <p>
	 * Returns whether or not the text has been modified and needs to be
	 * saved.
	 * </p>
	 *
	 * @return whether or not the text has been modified.
	 *
	 * @see #undo()
	 * @see #redo()
	 */
	public boolean getModified()
	{
		return saveIndex != changeIndex;
	}

	/**
	 * <p>
	 * Undoes the last change.
	 * </p><p>
	 * Currently only simply changes are recorded.
	 * </p>
	 *
	 * @see #redo()
	 * @see #getModified()
	 */
	public void undo()
	{
		if(changeIndex < 1)
			return;
		undoing = true;
		changeIndex--;
		ExtendedModifyEvent change = changes.remove(changeIndex);
		currentText.replaceTextRange(change.start, change.length, change.replacedText);
		currentText.setCaretOffset(change.start + change.replacedText.length());
		scrollToCaret();
	}

	/**
	 * <p>
	 * Undoes the last undo.
	 * </p>
	 *
	 * @see #undo()
	 * @see #getModified()
	 */
	public void redo()
	{
		if(changeIndex == changes.size())
			return;
		redoing = true;
		ExtendedModifyEvent change = changes.remove(changeIndex);
		currentText.replaceTextRange(change.start, change.length, change.replacedText);
		currentText.setCaretOffset(change.start + change.replacedText.length());
		scrollToCaret();
	}

	private boolean isFirstLineOnPage(int index)
	{
		return index % linesPerPage == 0;
	}

	/**
	 * <p>
	 * Reads data in BRF format from <code>Reader</code>.
	 * </p><p>
	 * An attempt is made to determine the number of lines per page.
	 * </p>
	 *
	 * @param reader the reader stream from which to read the data.
	 *
	 * @exception IOException
	 *
	 * @see #writeBRF(Writer)
	 */
	public void readBRF(Reader reader) throws IOException
	{
		StringBuilder stringBuilder = new StringBuilder();
		boolean checkLinesPerPage = true;
		boolean removeFormFeed = true;
		char buffer[] = new char[65536];
		int cnt, trim;

		eol = null;
		while((cnt = reader.read(buffer)) > 0)
		{
			//   see if lines per page can be determined
			if(checkLinesPerPage)
			{
				checkLinesPerPage = false;
				int lines = 0, i;
				outer:for(i = 0; i < cnt; i++)
				switch(buffer[i])
				{
				case '\n':  lines++;  break;

				case '\r':

					if(eol == null)
						eol = "\r\n";
					break;

				case 0xc:

					linesPerPage = lines;
					break outer;
				}

				if(eol == null)
					eol = "\n";
				if(i == cnt)
					removeFormFeed = false;
			}

			//   remove form feeds
			if(removeFormFeed)
			{
				trim = 0;
				for(int i = 0; i < cnt; i++)
				if(buffer[i] != 0xc)
				{
					buffer[trim] = buffer[i];
					trim++;
				}
			}
			else
				trim = cnt;

			stringBuilder.append(new String(buffer, 0, trim));
		}

		content.setText(stringBuilder.toString());
		clearChanges();
	}

	/**
	 * <p>
	 * Writes data in BRF format to <code>Writer</code>.
	 * </p>
	 *
	 * @param writer the writer stream to write the data.
	 *
	 * @exception IOException
	 *
	 * @see #readBRF(Reader)
	 */
	public void writeBRF(Writer writer) throws IOException
	{
		//   write first line
		String line = content.getLine(0);
		if(line.length() > 0 && line.charAt(line.length() - 1) == PARAGRAPH_END)
			writer.write(line.substring(0, line.length() - 1));
		else
			writer.write(line);

		//   write remaining lines
		for(int i = 1; i < content.getLineCount(); i++)
		{
			writer.write(eol);
			if(isFirstLineOnPage(i))
				writer.write(0xc);
			line = content.getLine(i);
			if(line.length() > 0 && line.charAt(line.length() - 1) == PARAGRAPH_END)
				writer.write(line.substring(0, line.length() - 1));
			else
				writer.write(line);
		}

		writer.flush();
		resetChanges();
	}

	/**
	 * <p>
	 * Reads data in BrailleZephyr file format from <code>Reader</code>.
	 * </p>
	 *
	 * @param reader the reader stream from which to read the data.
	 *
	 * @exception IOException
	 *
	 * @see #writeBZY(Writer)
	 */
	public void readBZY(Reader reader) throws IOException
	{
		content.setText("");
		eol = System.getProperty("line.separator");
		BufferedReader buffer = new BufferedReader(reader);

		//TODO:  verify file format

		//   read configuration lines
		String line = buffer.readLine();
		charsPerLine = Integer.parseInt(line.substring(17));
		line = buffer.readLine();
		linesPerPage = Integer.parseInt(line.substring(17));

		//   read text
		while((line = buffer.readLine()) != null)
		{
			if(line.length() > 0 && line.charAt(line.length() - 1) == 0xb6)
				content.replaceTextRange(content.getCharCount(), 0, line.substring(0, line.length() - 1) + PARAGRAPH_END + eol);
			else
				content.replaceTextRange(content.getCharCount(), 0, line + eol);
		}

		clearChanges();
	}

	/**
	 * <p>
	 * Writes data in BrailleZephyr file format to <code>Writer</code>.
	 * </p>
	 *
	 * @param writer the writer stream to write the data.
	 *
	 * @exception IOException
	 *
	 * @see #readBZY(Reader)
	 */
	public void writeBZY(Writer writer) throws IOException
	{
		//   write configuration lines
		writer.write("Chars Per Line:  " + charsPerLine + eol);
		writer.write("Lines Per Page:  " + linesPerPage + eol);

		//   write text
		for(int i = 0; i < content.getLineCount(); i++)
		{
			String line = content.getLine(i);
			if(line.length() > 0 && line.charAt(line.length() - 1) == PARAGRAPH_END)
				writer.write(line.substring(0, line.length() - 1) + (char)0xb6 + eol);
			else
				writer.write(line + eol);
		}

		writer.flush();
		resetChanges();
	}

	/**
	 * <p>
	 * Wraps lines at and below the caret that exceede the number of
	 * characters per line.
	 * </p><p>
	 * Lines are wrapped at spaces between words when possible.  Lines that
	 * don't exceed the number of characters per line are not changed.
	 * </p><p>
	 * Currently this cannot be undone.
	 * </p>
	 */
	public void rewrapFromCaret()
	{
		for(int i = content.getLineAtOffset(currentText.getCaretOffset()); i < content.getLineCount(); i++)
		{
			String line = content.getLine(i);
			if(line.length() == 0)
				continue;

			//   line too long
			if(line.length() > charsPerLine)
			{
				int wordWrap, wordEnd;

				//   find beginning of word being wrapped
				if(line.charAt(charsPerLine) != ' ')
				{
					for(wordWrap = charsPerLine; wordWrap > charsPerLine / 2; wordWrap--)
						if(line.charAt(wordWrap) == ' ')
							break;
					if(wordWrap == charsPerLine / 2)
						continue;
					wordWrap++;
				}
				else
				{
					for(wordWrap = charsPerLine; wordWrap < line.length(); wordWrap++)
						if(line.charAt(wordWrap) != ' ')
							break;
					if(wordWrap == line.length())
						continue;
				}

				//   find end of word before word being wrapped
				for(wordEnd = wordWrap - 1; wordEnd > charsPerLine / 4; wordEnd--)
					if(line.charAt(wordEnd) != ' ')
						break;
				if(wordEnd == charsPerLine / 4)
					continue;
				wordEnd++;

				//   build replacement text
				int length = line.length();
				StringBuilder builder = new StringBuilder();
				builder.append(line.substring(0, wordEnd)).append(eol).append(line.substring(wordWrap, length));
				if(length > 0 && line.charAt(length - 1) != PARAGRAPH_END)
				if(i < content.getLineCount() - 1)
				{
					String next = content.getLine(i + 1);
					builder.append(" ").append(next);
					length += eol.length() + next.length();
				}

				content.replaceTextRange(content.getOffsetAtLine(i), length, builder.toString());
			}
			else if(line.length() > 0 && line.charAt(line.length() - 1) == PARAGRAPH_END)
				break;
		}

		clearChanges();
	}

	private class FocusHandler implements FocusListener
	{
		private final StyledText source;

		private FocusHandler(StyledText source)
		{
			this.source = source;
		}

		@Override
		public void focusGained(FocusEvent e)
		{
			currentText = source;
		}

		@Override
		public void focusLost(FocusEvent event){}
	}

	private class CaretHandler implements CaretListener
	{
		private final StyledText source, other;

		private int prevCaretOffset, prevLineIndex;

		private CaretHandler(StyledText source, StyledText other)
		{
			this.source = source;
			this.other = other;
		}

		@Override
		public void caretMoved(CaretEvent event)
		{
			int caretOffset = source.getCaretOffset();
			int lineIndex = source.getLineAtOffset(caretOffset);
			int lineOffset = source.getOffsetAtLine(lineIndex);

			//   play margin bell
			if(clipMarginBell != null && bellLineMargin > 0)
			if(bellLineMargin > 0 && caretOffset == prevCaretOffset + 1)
			{
				if(caretOffset - lineOffset == bellLineMargin)
				if(!clipMarginBell.isActive())
				{
					clipMarginBell.setFramePosition(0);
					clipMarginBell.start();
				}
			}
			prevCaretOffset = caretOffset;

			//   scroll other text to match current
			if(source != currentText)
				return;
			int sourceLinePixel = source.getLinePixel(lineIndex);
			int otherhLineHeight = other.getLineHeight();
			int otherLineRealPixel = lineIndex * otherhLineHeight;
			other.setTopPixel(otherLineRealPixel - sourceLinePixel);

			//   redraw page lines
			if(lineIndex != prevLineIndex)
				redraw();
			prevLineIndex = lineIndex;
		}
	}

	private class PaintHandler implements PaintListener
	{
		private final StyledText source;

		private PaintHandler(StyledText source)
		{
			this.source = source;
		}

		@Override
		public void paintControl(PaintEvent event)
		{
			event.gc.setForeground(color);
			event.gc.setBackground(color);

			int lineHeight = source.getLineHeight();
			int drawHeight = source.getClientArea().height;
			int drawWidth = source.getClientArea().width;
			int rightMargin = event.gc.getFontMetrics().getAverageCharWidth() * charsPerLine;

			//   draw right margin
			event.gc.drawLine(rightMargin, 0, rightMargin, drawHeight);

			int at;
			for(int i = source.getTopIndex(); i < source.getLineCount(); i++)
			{
				//   draw page lines
				at = source.getLinePixel(i);
				if(isFirstLineOnPage(i))
					event.gc.drawLine(0, at, drawWidth, at);

				//   draw paragraph end markers
				String line = source.getLine(i);
				if(line.length() > 0 && line.charAt(line.length() - 1) == PARAGRAPH_END)
				{
					Point point = event.gc.stringExtent(line);
					int span = point.y / 2;
					event.gc.fillOval(point.x + span / 2, at + span / 2, span, span);
				}

				//   check if line still visible
				if(at + lineHeight > drawHeight)
					break;
			}
		}
	}

	private class BrailleKeyHandler implements KeyListener, VerifyKeyListener
	{
		private static final String asciiBraille = " A1B'K2L@CIF/MSP\"E3H9O6R^DJG>NTQ,*5<-U8V.%[$+X!&;:4\\0Z7(_?W]#Y)=";
		private final boolean brailleEntry;

		private char dotState, dotChar = 0x2800;
		private int prevLine;

		private BrailleKeyHandler(boolean brailleEntry)
		{
			this.brailleEntry = brailleEntry;
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			switch(event.character)
			{
			case 'f':

				dotState |= 0x01;
				dotChar |= 0x01;
				break;

			case 'd':

				dotState |= 0x02;
				dotChar |= 0x02;
				break;

			case 's':

				dotState |= 0x04;
				dotChar |= 0x04;
				break;

			case 'j':

				dotState |= 0x08;
				dotChar |= 0x08;
				break;

			case 'k':

				dotState |= 0x10;
				dotChar |= 0x10;
				break;

			case 'l':

				dotState |= 0x20;
				dotChar |= 0x20;
				break;
			}
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
			if(windowBug)
			    dotState = 0;
			else switch(event.character)
			{
			case 'f':

				dotState &= ~0x01;
				break;

			case 'd':

				dotState &= ~0x02;
				break;

			case 's':

				dotState &= ~0x04;
				break;

			case 'j':

				dotState &= ~0x08;
				break;

			case 'k':

				dotState &= ~0x10;
				break;

			case 'l':

				dotState &= ~0x20;
				break;
			}

			//   insert resulting braille character
			if(dotState == 0 && (dotChar & 0xff) != 0)
			{
				dotChar = asciiBraille.charAt((dotChar & 0xff));
				brailleText.insert(Character.toString(dotChar));
				brailleText.setCaretOffset(brailleText.getCaretOffset() + 1);
				dotChar = 0x2800;
			}
		}

		@Override
		public void verifyKey(VerifyEvent event)
		{
			StyledText styledText = (StyledText)event.widget;

			if(event.keyCode == '\r' || event.keyCode == '\n')
			if((event.stateMask & SWT.SHIFT) != 0)
			{
				//   toggle paragraph end character
				event.doit = false;
				int index = styledText.getLineAtOffset(styledText.getCaretOffset());
				String line = styledText.getLine(index);
				if(line.length() > 0)
				if(line.charAt(line.length() - 1) != PARAGRAPH_END)
					styledText.replaceTextRange(styledText.getOffsetAtLine(index), line.length(), line + Character.toString(PARAGRAPH_END));
				else
					styledText.replaceTextRange(styledText.getOffsetAtLine(index), line.length(), line.substring(0, line.length() - 1));
				return;
			}
			else
			{
				//   play page bell
				int index = styledText.getLineAtOffset(styledText.getCaretOffset());
				if(index == prevLine + 1 && index == bellPageMargin - 2)
				if(!clipPageBell.isActive())
				{
					clipPageBell.setFramePosition(0);
					clipPageBell.start();
				}
				prevLine = index;
			}

			//   check if using braille entry
			if(brailleEntry)
			if(event.character > ' ' && event.character < 0x7f)
				event.doit = false;
		}
	}

	private class ExtendedModifyHandler implements ExtendedModifyListener
	{
		private final StyledText source;

		private ExtendedModifyHandler(StyledText source)
		{
			this.source = source;
		}

		@Override
		public void modifyText(ExtendedModifyEvent event)
		{
			//TODO:  is this ever not true?
			if(source != currentText)
				return;

			if(undoing)
				changes.add(changeIndex, event);
			else if(redoing)
				changes.add(changeIndex++, event);
			else
			{
				if(changeIndex < changes.size())
					changes.subList(changeIndex, changes.size()).clear();
				changes.add(changeIndex++, event);
			}
			undoing = redoing = false;
		}
	}
}

package com.cliprecorder;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel styled after the user's mockup: quick action buttons, recent
 * clip cards, and a status footer. All icons are vector-drawn.
 */
@Slf4j
class ClipRecorderPanel extends PluginPanel
{
	private static final int MAX_LISTED = 10;

	// Palette (matches the mockup)
	private static final Color BG = new Color(0x17, 0x19, 0x1c);
	private static final Color CARD = new Color(0x24, 0x27, 0x2b);
	private static final Color CARD_HOVER = new Color(0x2d, 0x31, 0x36);
	private static final Color ORANGE = new Color(0xf0, 0x90, 0x22);
	private static final Color RED = new Color(0xe5, 0x48, 0x3c);
	private static final Color TEXT = new Color(0xe6, 0xe6, 0xe6);
	private static final Color SUBTEXT = new Color(0x9a, 0xa0, 0xa6);
	private static final Color GREEN = new Color(0x3a, 0xc5, 0x69);
	private static final Color GRAY_DOT = new Color(0x6b, 0x71, 0x78);

	private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);
	private static final Font SECTION_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font BUTTON_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
	private static final Font ROW_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font ROW_SUB_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a");

	private final ClipRecorderPlugin plugin;
	private final RecordIcon headerIcon = new RecordIcon();
	private final JPanel clipList = new JPanel();
	private final JLabel countBadge = new JLabel("0", SwingConstants.CENTER);
	private final JLabel statusLabel = new JLabel("Idle");
	private final StatusDot statusDot = new StatusDot();
	private final JTextField folderPathField = new JTextField();

	private final Timer pulseTimer;
	private final Timer statusTimer;
	private volatile boolean saving;

	ClipRecorderPanel(ClipRecorderPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(BG);
		setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		// ---- Header: record icon, title, kebab menu ----
		JLabel title = new JLabel("Instant Replay");
		title.setFont(TITLE_FONT);
		title.setForeground(TEXT);

		KebabButton headerMenu = new KebabButton(true);
		headerMenu.onClick(e ->
		{
			JPopupMenu menu = new JPopupMenu();
			JMenuItem choose = new JMenuItem("Choose clips folder...");
			choose.addActionListener(a -> chooseFolder());
			menu.add(choose);
			menu.show(headerMenu, 0, headerMenu.getHeight());
		});

		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBackground(BG);
		header.add(headerIcon, BorderLayout.WEST);
		header.add(title, BorderLayout.CENTER);
		header.add(headerMenu, BorderLayout.EAST);

		// ---- Quick actions ----
		JLabel quickLabel = sectionLabel("Quick Actions");

		RoundedButton saveButton = new RoundedButton("Save clip now", ORANGE, true, ClipRecorderPanel::paintRecordGlyph);
		saveButton.onClick(e -> plugin.saveManualClip());

		RoundedButton refreshButton = new RoundedButton("Refresh list", TEXT, false, ClipRecorderPanel::paintRefreshGlyph);
		refreshButton.onClick(e -> refresh());

		JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
		actions.setBackground(BG);
		actions.add(quickLabel);
		actions.add(Box.createVerticalStrut(8));
		actions.add(saveButton);
		actions.add(Box.createVerticalStrut(8));
		actions.add(refreshButton);
		actions.add(Box.createVerticalStrut(14));
		actions.add(buildFolderRow());

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(BG);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(header);
		top.add(Box.createVerticalStrut(16));
		top.add(actions);
		top.add(Box.createVerticalStrut(16));

		// ---- Recent clips ----
		JLabel recentLabel = sectionLabel("Recent Clips");
		countBadge.setFont(ROW_SUB_FONT);
		countBadge.setForeground(TEXT);
		countBadge.setOpaque(false);
		countBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
		JPanel badgeWrap = new JPanel(new BorderLayout())
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g;
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(CARD_HOVER);
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
			}
		};
		badgeWrap.setOpaque(false);
		badgeWrap.add(countBadge, BorderLayout.CENTER);

		JPanel recentHeader = new JPanel(new BorderLayout());
		recentHeader.setBackground(BG);
		recentHeader.add(recentLabel, BorderLayout.WEST);
		recentHeader.add(badgeWrap, BorderLayout.EAST);

		clipList.setLayout(new BoxLayout(clipList, BoxLayout.Y_AXIS));
		clipList.setBackground(BG);

		JPanel center = new JPanel(new BorderLayout(0, 8));
		center.setBackground(BG);
		center.add(recentHeader, BorderLayout.NORTH);
		center.add(clipList, BorderLayout.CENTER);

		// ---- Status footer ----
		statusLabel.setFont(SECTION_FONT);
		statusLabel.setForeground(SUBTEXT);

		JPanel footer = new JPanel(new BorderLayout(8, 0));
		footer.setBackground(BG);
		footer.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		footer.setToolTipText("<html><b>Buffering</b>: the last moments of gameplay are kept in memory only,"
			+ " constantly overwritten.<br><b>Idle</b>: not logged in - nothing is captured at all."
			+ "<br>This plugin sees only the game's rendered frames, never your desktop or other windows."
			+ "<br>Clips are written to your PC only when a trigger fires or you save manually."
			+ " Nothing is ever uploaded.</html>");
		WaveformIcon wave = new WaveformIcon();
		JPanel footerLeft = new JPanel(new BorderLayout(6, 0));
		footerLeft.setBackground(BG);
		footerLeft.add(wave, BorderLayout.WEST);
		footerLeft.add(statusLabel, BorderLayout.CENTER);
		footer.add(footerLeft, BorderLayout.CENTER);
		footer.add(statusDot, BorderLayout.EAST);

		add(top, BorderLayout.NORTH);
		add(center, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);

		pulseTimer = new Timer(40, e ->
		{
			if (saving)
			{
				headerIcon.tickPulse();
			}
		});
		pulseTimer.start();

		statusTimer = new Timer(1000, e -> updateStatus());
		statusTimer.start();

		refresh();
		updateStatus();
	}

	void dispose()
	{
		pulseTimer.stop();
		statusTimer.stop();
	}

	/**
	 * Called around clip writes so the header icon pulses red while saving.
	 */
	void setSaving(boolean isSaving)
	{
		saving = isSaving;
		headerIcon.setSaving(isSaving);
		updateStatus();
	}

	private void updateStatus()
	{
		if (saving)
		{
			statusLabel.setText("Saving clip...");
			statusLabel.setForeground(ORANGE);
			statusDot.setColor(ORANGE);
		}
		else if (plugin.isBuffering())
		{
			statusLabel.setText("Buffering");
			statusLabel.setForeground(SUBTEXT);
			statusDot.setColor(GREEN);
		}
		else
		{
			statusLabel.setText("Idle");
			statusLabel.setForeground(SUBTEXT);
			statusDot.setColor(GRAY_DOT);
		}
	}

	/**
	 * Rebuild the clip list. Must run on the Swing EDT.
	 */
	void refresh()
	{
		clipList.removeAll();
		folderPathField.setText(plugin.getClipDirectory().getAbsolutePath());
		folderPathField.setCaretPosition(0);

		// Badge shows the total clip count; the list shows only the newest few
		List<File> allClips = plugin.listRecentClips(Integer.MAX_VALUE);
		countBadge.setText(String.valueOf(allClips.size()));
		List<File> clips = allClips.size() > MAX_LISTED ? allClips.subList(0, MAX_LISTED) : allClips;

		if (clips.isEmpty())
		{
			JLabel empty = new JLabel("No clips yet", SwingConstants.CENTER);
			empty.setFont(ROW_FONT);
			empty.setForeground(SUBTEXT);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			clipList.add(Box.createVerticalStrut(8));
			clipList.add(empty);
		}
		else
		{
			for (File clip : clips)
			{
				ClipRow row = new ClipRow(clip);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				clipList.add(row);
				clipList.add(Box.createVerticalStrut(8));
			}
		}

		clipList.revalidate();
		clipList.repaint();
	}

	private JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(SECTION_FONT);
		label.setForeground(SUBTEXT);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private void chooseFolder()
	{
		JFileChooser chooser = new JFileChooser(plugin.getClipDirectory());
		chooser.setDialogTitle("Choose clips folder");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			plugin.setOutputFolder(chooser.getSelectedFile());
			refresh();
		}
	}

	/**
	 * The plugin can't launch a file manager (no allowed API for it), so show
	 * the clips-folder path as read-only, selectable text the user can copy.
	 */
	private JComponent buildFolderRow()
	{
		JLabel label = sectionLabel("Clips folder");

		folderPathField.setEditable(false);
		folderPathField.setText(plugin.getClipDirectory().getAbsolutePath());
		folderPathField.setCaretPosition(0);
		folderPathField.setBackground(CARD);
		folderPathField.setForeground(SUBTEXT);
		folderPathField.setFont(ROW_SUB_FONT);
		folderPathField.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		folderPathField.setToolTipText("Select and copy this path to open it in your file manager");
		folderPathField.setAlignmentX(Component.LEFT_ALIGNMENT);
		folderPathField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(BG);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(label);
		p.add(Box.createVerticalStrut(6));
		p.add(folderPathField);
		return p;
	}

	// ---- Components ----

	/**
	 * Header record icon: orange ring + dot; pulses red while saving.
	 */
	private static class RecordIcon extends JPanel
	{
		private boolean saving;
		private float phase;

		RecordIcon()
		{
			setPreferredSize(new Dimension(22, 22));
			setOpaque(false);
		}

		void setSaving(boolean saving)
		{
			this.saving = saving;
			if (!saving)
			{
				phase = 0;
			}
			repaint();
		}

		void tickPulse()
		{
			phase += 0.15f;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Color color = ORANGE;
			if (saving)
			{
				// Oscillate brightness for the pulse effect
				float a = 0.55f + 0.45f * (float) Math.abs(Math.sin(phase));
				color = new Color(RED.getRed(), RED.getGreen(), RED.getBlue(), (int) (a * 255));
			}
			int s = Math.min(getWidth(), getHeight());
			g2.setColor(color);
			g2.setStroke(new BasicStroke(2f));
			g2.drawOval(2, 2, s - 5, s - 5);
			int dot = (s - 4) / 2;
			g2.fillOval((s - dot) / 2, (s - dot) / 2, dot, dot);
			g2.dispose();
		}
	}

	private static class StatusDot extends JPanel
	{
		private Color color = GRAY_DOT;

		StatusDot()
		{
			setPreferredSize(new Dimension(12, 12));
			setOpaque(false);
		}

		void setColor(Color color)
		{
			this.color = color;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			int s = Math.min(getWidth(), getHeight());
			g2.fillOval((getWidth() - s) / 2 + 1, (getHeight() - s) / 2 + 1, s - 2, s - 2);
		}
	}

	private static class WaveformIcon extends JPanel
	{
		WaveformIcon()
		{
			setPreferredSize(new Dimension(16, 14));
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(SUBTEXT);
			int cy = getHeight() / 2;
			int[] heights = {4, 8, 12, 8, 4};
			int x = 1;
			for (int h : heights)
			{
				g2.fillRoundRect(x, cy - h / 2, 2, h, 2, 2);
				x += 3;
			}
		}
	}

	@FunctionalInterface
	private interface GlyphPainter
	{
		void paint(Graphics2D g2, int x, int y, int size, Color color);
	}

	/**
	 * Rounded action button: icon + label, hover highlight, optional accent
	 * outline (the orange "Save clip now" style).
	 */
	private static class RoundedButton extends JPanel
	{
		private final String text;
		private final Color accent;
		private final boolean outlined;
		private final GlyphPainter glyph;
		private boolean hover;
		private MouseAdapter clickHandler;

		RoundedButton(String text, Color accent, boolean outlined, GlyphPainter glyph)
		{
			this.text = text;
			this.accent = accent;
			this.outlined = outlined;
			this.glyph = glyph;
			setOpaque(false);
			setPreferredSize(new Dimension(0, 38));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
		}

		void onClick(java.util.function.Consumer<MouseEvent> action)
		{
			if (clickHandler != null)
			{
				removeMouseListener(clickHandler);
			}
			clickHandler = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					action.accept(e);
				}
			};
			addMouseListener(clickHandler);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();

			g2.setColor(hover ? CARD_HOVER : CARD);
			g2.fillRoundRect(0, 0, w, h, 14, 14);
			if (outlined)
			{
				g2.setColor(accent);
				g2.setStroke(new BasicStroke(1.5f));
				g2.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);
			}

			int iconSize = 14;
			int iconX = 14;
			int iconY = (h - iconSize) / 2;
			glyph.paint(g2, iconX, iconY, iconSize, accent);

			g2.setFont(BUTTON_FONT);
			g2.setColor(accent);
			int textY = (h + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2;
			g2.drawString(text, iconX + iconSize + 12, textY);
			g2.dispose();
		}
	}

	private static class KebabButton extends JPanel
	{
		private final boolean vertical;
		private boolean hover;

		KebabButton(boolean vertical)
		{
			this.vertical = vertical;
			setPreferredSize(new Dimension(22, 22));
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
		}

		void onClick(java.util.function.Consumer<MouseEvent> action)
		{
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					action.accept(e);
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(hover ? TEXT : SUBTEXT);
			int cx = getWidth() / 2;
			int cy = getHeight() / 2;
			for (int i = -1; i <= 1; i++)
			{
				int x = vertical ? cx : cx + i * 5;
				int y = vertical ? cy + i * 5 : cy;
				g2.fillOval(x - 1, y - 1, 3, 3);
			}
		}
	}

	/**
	 * One recent-clip card: play glyph, filename, time + size, kebab menu.
	 */
	private class ClipRow extends JPanel
	{
		private boolean hover;

		ClipRow(File clip)
		{
			setOpaque(false);
			setLayout(new BorderLayout(10, 0));
			setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
			setToolTipText(clip.getAbsolutePath());

			JPanel playWrap = new JPanel(new BorderLayout())
			{
				@Override
				protected void paintComponent(Graphics g)
				{
					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					paintClipGlyph(g2, 1, (getHeight() - 12) / 2, 14, TEXT);
				}
			};
			playWrap.setOpaque(false);
			playWrap.setPreferredSize(new Dimension(16, 16));

			JLabel name = new JLabel(clip.getName());
			name.setFont(ROW_FONT);
			name.setForeground(TEXT);

			String time = TIME_FORMAT.format(Instant.ofEpochMilli(clip.lastModified()).atZone(ZoneId.systemDefault()));
			JLabel timeLabel = new JLabel(time);
			timeLabel.setFont(ROW_SUB_FONT);
			timeLabel.setForeground(SUBTEXT);

			JLabel sizeLabel = new JLabel(formatSize(clip.length()));
			sizeLabel.setFont(ROW_SUB_FONT);
			sizeLabel.setForeground(SUBTEXT);

			JPanel subRow = new JPanel(new BorderLayout());
			subRow.setOpaque(false);
			subRow.add(timeLabel, BorderLayout.WEST);
			subRow.add(sizeLabel, BorderLayout.EAST);

			JPanel textCol = new JPanel();
			textCol.setOpaque(false);
			textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
			name.setAlignmentX(Component.LEFT_ALIGNMENT);
			subRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			textCol.add(name);
			textCol.add(Box.createVerticalStrut(2));
			textCol.add(subRow);

			KebabButton kebab = new KebabButton(false);
			kebab.onClick(e -> showMenu(kebab, clip));

			add(playWrap, BorderLayout.WEST);
			add(textCol, BorderLayout.CENTER);
			add(kebab, BorderLayout.EAST);

			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
		}

		private void showMenu(Component anchor, File clip)
		{
			JPopupMenu menu = new JPopupMenu();
			JMenuItem delete = new JMenuItem("Delete");
			delete.addActionListener(a ->
			{
				int ok = JOptionPane.showConfirmDialog(this,
					"Delete " + clip.getName() + "?", "Delete clip", JOptionPane.YES_NO_OPTION);
				if (ok == JOptionPane.YES_OPTION && clip.delete())
				{
					refresh();
				}
			});
			menu.add(delete);
			menu.show(anchor, 0, anchor.getHeight());
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(hover ? CARD_HOVER : CARD);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
			g2.dispose();
			super.paintComponent(g);
		}
	}

	// ---- Glyph painters (vector icons, no image resources) ----

	private static void paintRecordGlyph(Graphics2D g2, int x, int y, int size, Color color)
	{
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1.6f));
		g2.drawOval(x, y, size - 1, size - 1);
		int dot = size / 2;
		g2.fillOval(x + (size - dot) / 2, y + (size - dot) / 2, dot, dot);
	}

	private static void paintRefreshGlyph(Graphics2D g2, int x, int y, int size, Color color)
	{
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1.6f));
		g2.drawArc(x + 1, y + 1, size - 3, size - 3, 30, 280);
		// Arrow head at the arc's start
		int ax = x + size - 3;
		int ay = y + 3;
		Path2D arrow = new Path2D.Float();
		arrow.moveTo(ax - 3, ay - 2);
		arrow.lineTo(ax + 2, ay);
		arrow.lineTo(ax - 1, ay + 4);
		g2.draw(arrow);
	}

	// A neutral "video clip" mark (a film frame) - the panel can't launch
	// playback, so this is decorative, not a play button.
	private static void paintClipGlyph(Graphics2D g2, int x, int y, int size, Color color)
	{
		g2.setColor(color);
		g2.setStroke(new BasicStroke(1.3f));
		g2.drawRoundRect(x, y + 1, size - 2, size - 3, 3, 3);
		int mid = y + (size - 1) / 2;
		g2.drawLine(x + 3, mid, x + size - 5, mid);
	}

	private static String formatSize(long bytes)
	{
		if (bytes >= 1024 * 1024)
		{
			return (bytes / (1024 * 1024)) + " MB";
		}
		return (bytes / 1024) + " KB";
	}
}

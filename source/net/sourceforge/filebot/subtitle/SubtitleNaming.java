package net.sourceforge.filebot.subtitle;

import static net.sourceforge.tuned.FileUtilities.*;

import java.io.File;

import net.sourceforge.filebot.web.SubtitleDescriptor;

public enum SubtitleNaming {

	ORIGINAL {

		@Override
		public String format(File video, SubtitleDescriptor subtitle) {
			return String.format("%s.%s", getName(video), subtitle.getType());
		}

		@Override
		public String toString() {
			return "Keep Original";
		}
	},

	MATCH_VIDEO {

		@Override
		public String format(File video, SubtitleDescriptor subtitle) {
			return SubtitleUtilities.formatSubtitle(getName(video), null, subtitle.getType());
		}

		@Override
		public String toString() {
			return "Match Video";
		}
	},

	MATCH_VIDEO_ADD_LANGUAGE_TAG {

		@Override
		public String format(File video, SubtitleDescriptor subtitle) {
			return SubtitleUtilities.formatSubtitle(getName(video), subtitle.getLanguageName(), subtitle.getType());
		}

		@Override
		public String toString() {
			return "By Video/Language";
		}
	};

	public abstract String format(File video, SubtitleDescriptor subtitle);

}

package com.speakai.app.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.speakai.app.models.Book;
import com.speakai.app.models.Chapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AppDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "speakai_library.db";
    private static final int DATABASE_VERSION = 2;

    // Books table
    public static final String TABLE_BOOKS = "books";
    public static final String COL_BOOK_ID = "id";
    public static final String COL_BOOK_TITLE = "title";
    public static final String COL_BOOK_FILENAME = "filename";
    public static final String COL_BOOK_TOTAL_CHAPTERS = "total_chapters";
    public static final String COL_BOOK_OVERALL_PROGRESS = "overall_progress";
    public static final String COL_BOOK_CREATED_AT = "created_at";
    public static final String COL_BOOK_POLISH_JOB_ID = "polish_job_id";

    // Chapters table
    public static final String TABLE_CHAPTERS = "chapters";
    public static final String COL_CH_ID = "id";
    public static final String COL_CH_BOOK_ID = "book_id";
    public static final String COL_CH_INDEX = "chapter_index";
    public static final String COL_CH_TITLE = "title";
    public static final String COL_CH_CONTENT = "content_text";
    public static final String COL_CH_PROGRESS = "progress_percent";
    public static final String COL_CH_LAST_CHUNK = "last_chunk_index";
    public static final String COL_CH_DOWNLOADED = "is_downloaded";
    public static final String COL_CH_AUDIO_PATH = "local_audio_path";
    public static final String COL_CH_CLEAN_STATUS = "clean_status";

    private final Context context;

    public AppDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createBooksTable = "CREATE TABLE " + TABLE_BOOKS + " (" +
                COL_BOOK_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_BOOK_TITLE + " TEXT NOT NULL, " +
                COL_BOOK_FILENAME + " TEXT, " +
                COL_BOOK_TOTAL_CHAPTERS + " INTEGER DEFAULT 1, " +
                COL_BOOK_OVERALL_PROGRESS + " INTEGER DEFAULT 0, " +
                COL_BOOK_CREATED_AT + " INTEGER, " +
                COL_BOOK_POLISH_JOB_ID + " TEXT DEFAULT '');";

        String createChaptersTable = "CREATE TABLE " + TABLE_CHAPTERS + " (" +
                COL_CH_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_CH_BOOK_ID + " INTEGER, " +
                COL_CH_INDEX + " INTEGER, " +
                COL_CH_TITLE + " TEXT NOT NULL, " +
                COL_CH_CONTENT + " TEXT, " +
                COL_CH_PROGRESS + " INTEGER DEFAULT 0, " +
                COL_CH_LAST_CHUNK + " INTEGER DEFAULT 0, " +
                COL_CH_DOWNLOADED + " INTEGER DEFAULT 0, " +
                COL_CH_AUDIO_PATH + " TEXT, " +
                COL_CH_CLEAN_STATUS + " TEXT DEFAULT 'ready', " +
                "FOREIGN KEY(" + COL_CH_BOOK_ID + ") REFERENCES " + TABLE_BOOKS + "(" + COL_BOOK_ID + ") ON DELETE CASCADE);";

        db.execSQL(createBooksTable);
        db.execSQL(createChaptersTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_BOOKS + " ADD COLUMN " + COL_BOOK_POLISH_JOB_ID + " TEXT DEFAULT ''");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_CHAPTERS + " ADD COLUMN " + COL_CH_CLEAN_STATUS + " TEXT DEFAULT 'ready'");
            } catch (Exception ignored) {}
        }
    }

    public long insertBook(Book book, List<Chapter> chapters) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        long bookId = -1;
        try {
            ContentValues bookValues = new ContentValues();
            bookValues.put(COL_BOOK_TITLE, book.getTitle());
            bookValues.put(COL_BOOK_FILENAME, book.getFilename());
            bookValues.put(COL_BOOK_TOTAL_CHAPTERS, chapters.size());
            bookValues.put(COL_BOOK_OVERALL_PROGRESS, 0);
            bookValues.put(COL_BOOK_CREATED_AT, System.currentTimeMillis());
            bookValues.put(COL_BOOK_POLISH_JOB_ID, book.getPolishJobId() != null ? book.getPolishJobId() : "");

            bookId = db.insert(TABLE_BOOKS, null, bookValues);

            for (Chapter chapter : chapters) {
                ContentValues chValues = new ContentValues();
                chValues.put(COL_CH_BOOK_ID, bookId);
                chValues.put(COL_CH_INDEX, chapter.getChapterIndex());
                chValues.put(COL_CH_TITLE, chapter.getTitle());
                chValues.put(COL_CH_CONTENT, chapter.getContentText());
                chValues.put(COL_CH_PROGRESS, 0);
                chValues.put(COL_CH_LAST_CHUNK, 0);
                chValues.put(COL_CH_DOWNLOADED, 0);
                chValues.put(COL_CH_AUDIO_PATH, "");
                chValues.put(COL_CH_CLEAN_STATUS, chapter.getCleanStatus() != null ? chapter.getCleanStatus() : "ready");

                db.insert(TABLE_CHAPTERS, null, chValues);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return bookId;
    }

    public Book getBook(long bookId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOOKS, null, COL_BOOK_ID + "=?",
                new String[]{String.valueOf(bookId)}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            Book book = readBookFromCursor(cursor);
            cursor.close();
            return book;
        }
        if (cursor != null) cursor.close();
        return null;
    }

    public List<Book> getAllBooks() {
        List<Book> books = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOOKS, null, null, null, null, null, COL_BOOK_CREATED_AT + " DESC");

        if (cursor != null && cursor.moveToFirst()) {
            do {
                books.add(readBookFromCursor(cursor));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return books;
    }

    private Book readBookFromCursor(Cursor cursor) {
        int jobIdx = cursor.getColumnIndex(COL_BOOK_POLISH_JOB_ID);
        String polishJobId = jobIdx != -1 ? cursor.getString(jobIdx) : "";
        return new Book(
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_BOOK_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_BOOK_TITLE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_BOOK_FILENAME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_BOOK_TOTAL_CHAPTERS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_BOOK_OVERALL_PROGRESS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_BOOK_CREATED_AT)),
                polishJobId
        );
    }

    public List<Chapter> getChaptersForBook(long bookId) {
        List<Chapter> chapters = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CHAPTERS, null, COL_CH_BOOK_ID + "=?",
                new String[]{String.valueOf(bookId)}, null, null, COL_CH_INDEX + " ASC");

        if (cursor != null && cursor.moveToFirst()) {
            do {
                chapters.add(readChapterFromCursor(cursor));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return chapters;
    }

    public Chapter getChapter(long chapterId) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CHAPTERS, null, COL_CH_ID + "=?",
                new String[]{String.valueOf(chapterId)}, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            Chapter chapter = readChapterFromCursor(cursor);
            cursor.close();
            return chapter;
        }
        if (cursor != null) cursor.close();
        return null;
    }

    private Chapter readChapterFromCursor(Cursor cursor) {
        int cleanIdx = cursor.getColumnIndex(COL_CH_CLEAN_STATUS);
        String cleanStatus = cleanIdx != -1 ? cursor.getString(cleanIdx) : "ready";
        return new Chapter(
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_CH_ID)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COL_CH_BOOK_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_CH_INDEX)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CH_TITLE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CH_CONTENT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_CH_PROGRESS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_CH_LAST_CHUNK)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_CH_DOWNLOADED)) == 1,
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CH_AUDIO_PATH)),
                cleanStatus
        );
    }

    public void updateChapterCleanedText(long bookId, int chapterIndex, String cleanedText, String cleanStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CH_CONTENT, cleanedText);
        values.put(COL_CH_CLEAN_STATUS, cleanStatus);
        db.update(TABLE_CHAPTERS, values, COL_CH_BOOK_ID + "=? AND " + COL_CH_INDEX + "=?",
                new String[]{String.valueOf(bookId), String.valueOf(chapterIndex)});
    }

    public void updateChapterCleanedTextById(long chapterId, String cleanedText, String cleanStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CH_CONTENT, cleanedText);
        values.put(COL_CH_CLEAN_STATUS, cleanStatus);
        db.update(TABLE_CHAPTERS, values, COL_CH_ID + "=?", new String[]{String.valueOf(chapterId)});
    }

    public void updateChapterCleanStatus(long bookId, int chapterIndex, String cleanStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CH_CLEAN_STATUS, cleanStatus);
        db.update(TABLE_CHAPTERS, values, COL_CH_BOOK_ID + "=? AND " + COL_CH_INDEX + "=?",
                new String[]{String.valueOf(bookId), String.valueOf(chapterIndex)});
    }

    public void clearBookPolishJob(long bookId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_BOOK_POLISH_JOB_ID, "");
        db.update(TABLE_BOOKS, values, COL_BOOK_ID + "=?", new String[]{String.valueOf(bookId)});
    }

    public void markAllChaptersReady(long bookId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CH_CLEAN_STATUS, "ready");
        db.update(TABLE_CHAPTERS, values, COL_CH_BOOK_ID + "=?", new String[]{String.valueOf(bookId)});
        clearBookPolishJob(bookId);
    }

    public void updateChapterProgress(long chapterId, int progressPercent, int lastChunkIndex) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CH_PROGRESS, progressPercent);
        values.put(COL_CH_LAST_CHUNK, lastChunkIndex);
        db.update(TABLE_CHAPTERS, values, COL_CH_ID + "=?", new String[]{String.valueOf(chapterId)});

        Chapter ch = getChapter(chapterId);
        if (ch != null) {
            updateBookOverallProgress(ch.getBookId());
        }
    }

    private void updateBookOverallProgress(long bookId) {
        List<Chapter> chapters = getChaptersForBook(bookId);
        if (chapters.isEmpty()) return;

        int total = 0;
        for (Chapter c : chapters) {
            total += c.getProgressPercent();
        }
        int avg = total / chapters.size();

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_BOOK_OVERALL_PROGRESS, avg);
        db.update(TABLE_BOOKS, values, COL_BOOK_ID + "=?", new String[]{String.valueOf(bookId)});
    }

    public void setChapterDownloaded(long chapterId, boolean isDownloaded, String localAudioPath) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CH_DOWNLOADED, isDownloaded ? 1 : 0);
        values.put(COL_CH_AUDIO_PATH, localAudioPath);
        db.update(TABLE_CHAPTERS, values, COL_CH_ID + "=?", new String[]{String.valueOf(chapterId)});
    }

    public void deleteBook(long bookId) {
        SQLiteDatabase db = this.getWritableDatabase();

        File bookAudioDir = new File(context.getFilesDir(), "audio/book_" + bookId);
        if (bookAudioDir.exists()) {
            deleteRecursive(bookAudioDir);
        }

        db.delete(TABLE_CHAPTERS, COL_CH_BOOK_ID + "=?", new String[]{String.valueOf(bookId)});
        db.delete(TABLE_BOOKS, COL_BOOK_ID + "=?", new String[]{String.valueOf(bookId)});
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }
}

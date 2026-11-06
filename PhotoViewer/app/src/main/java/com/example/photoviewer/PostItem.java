package com.example.photoviewer;

import android.graphics.Bitmap;

public class PostItem {

    private final String author;
    private final String title;
    private final String text;
    private final String publishedDate;
    private final Bitmap image;

    public PostItem(String author, String title, String text, String publishedDate, Bitmap image) {
        this.author = author;
        this.title = title;
        this.text = text;
        this.publishedDate = publishedDate;
        this.image = image;
    }

    public String getAuthor() {
        return author;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public String getPublishedDate() {
        if (publishedDate == null || publishedDate.length() < 10) {
            return "";
        }
        String date = publishedDate.substring(0, 10);
        if (publishedDate.length() >= 16) {
            return date + " " + publishedDate.substring(11, 16);
        }
        return date;
    }

    public Bitmap getImage() {
        return image;
    }
}

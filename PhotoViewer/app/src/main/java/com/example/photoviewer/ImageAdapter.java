package com.example.photoviewer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(PostItem item);
    }

    private final List<PostItem> postList;
    private final OnItemClickListener listener;
    private final OnItemClickListener authorListener;

    public ImageAdapter(List<PostItem> postList, OnItemClickListener listener,
                        OnItemClickListener authorListener) {
        this.postList = postList;
        this.listener = listener;
        this.authorListener = authorListener;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        PostItem item = postList.get(position);

        holder.imageView.setImageBitmap(item.getImage());
        holder.textTitle.setText(item.getTitle());
        holder.textBody.setText(item.getText());
        holder.textAuthor.setText(item.getAuthor());
        holder.textDate.setText(item.getPublishedDate());

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
        holder.textAuthor.setOnClickListener(v -> {
            if (authorListener != null) {
                authorListener.onItemClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return postList.size();
    }

    public static class ImageViewHolder extends RecyclerView.ViewHolder {

        ImageView imageView;
        TextView textTitle;
        TextView textBody;
        TextView textAuthor;
        TextView textDate;

        public ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageViewItem);
            textTitle = itemView.findViewById(R.id.textTitle);
            textBody = itemView.findViewById(R.id.textBody);
            textAuthor = itemView.findViewById(R.id.textAuthor);
            textDate = itemView.findViewById(R.id.textDate);
        }
    }
}

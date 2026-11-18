package com.moadeal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Models {
    static final String BUYER = "host";
    static final String SELLER = "seller";
    static final String[] CATEGORIES = {"식품", "미용", "생필품"};
    static final String[] CATEGORY_KEYS = {"food", "beauty", "household"};

    static class Region {
        String city = "", district = "", neighborhood = "";

        Region() {}

        Region(String city, String district, String neighborhood) {
            this.city = city;
            this.district = district;
            this.neighborhood = neighborhood;
        }

        Region copy() { return new Region(city, district, neighborhood); }

        String label() {
            return String.join(" ", city, district, neighborhood).trim().replaceAll(" +", " ");
        }

        String display() { return city.isEmpty() ? "전체 지역" : label(); }

        boolean includes(Region other) {
            return (city.isEmpty() || city.equals(other.city))
                && (district.isEmpty() || district.equals(other.district) || other.district.startsWith(district + " "))
                && (neighborhood.isEmpty() || neighborhood.equals(other.neighborhood) || other.neighborhood.startsWith(neighborhood + " "));
        }
    }

    static class Product {
        String id, title, photo = "", description = "", owner = BUYER;
        boolean sellerProduct, remote, hasRemotePhoto;
        int remoteParticipantCount;
        Region region = new Region();
        int price, target = 6, category, sampleParticipants, likes, comparisonPrice, previewViews;
        long deadline = System.currentTimeMillis() + 7L * 86400000;
        String likesDate = "";
        int[] sampleViews = {0, 0, 0, 0, 0, 0, 0};
        final Map<Long, Integer> remoteViews = new HashMap<>();
        final Set<String> members = new HashSet<>();
        final List<Offer> offers = new ArrayList<>();
        final List<Question> questions = new ArrayList<>();
        Offer selected;

        boolean sellerListing() { return sellerProduct; }
        int joined() { return remote ? remoteParticipantCount : members.size() + sampleParticipants; }
        int remaining() { return target - joined(); }
        boolean completed() { return sellerListing() && joined() >= target; }
        int stage() { return selected != null || completed() ? 2 : joined() >= target ? 1 : 0; }
        boolean recruiting() { return stage() == 0 && deadline > System.currentTimeMillis(); }
        boolean awaitingOfferSelection() { return stage() == 1; }
        boolean canReceiveOffer() { return awaitingOfferSelection() && offers.isEmpty(); }
        String stageLabel() {
            if (sellerListing()) return completed() ? "판매완료" : recruiting() ? "판매진행중" : "모집 마감";
            return switch (stage()) {
                case 1 -> "제안 접수";
                case 2 -> "선택 완료";
                default -> recruiting() ? "모집 중" : "모집 마감";
            };
        }
        double savings() { return comparisonPrice > 0 ? (comparisonPrice - price) / (double) comparisonPrice : 0; }
        int viewsAt(int index) {
            if (remote) return remoteViews.getOrDefault(Store.epochDay() - 6 + index, 0);
            return sampleViews[index] + (index == 6 ? previewViews : 0);
        }
        int views() {
            int total = 0;
            for (int index = 0; index < 7; index++) total += viewsAt(index);
            return total;
        }
    }

    static class Offer {
        int price, quantity;
        String place, address, seller;
        long expiry;

        Offer(int price, int quantity, String place, String address, long expiry, String seller) {
            this.price = price;
            this.quantity = quantity;
            this.place = place;
            this.address = address;
            this.expiry = expiry;
            this.seller = seller;
        }
    }

    static class Question {
        String id, text, answer;
        Question(String text, String answer) { this.text = text; this.answer = answer; }
    }

    static class Message {
        String text, sender, name;
        Message(String text, String sender, String name) {
            this.text = text; this.sender = sender; this.name = name;
        }
    }

    static class Room {
        String id, name, description, area, category, photo = "";
        int sampleMembers;
        final Set<String> members = new HashSet<>();
        final List<Message> messages = new ArrayList<>();
    }
}

package com.xgamerstore.pdfforge;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;

import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/**
 * Google Play one-time, non-consumable purchase.
 * Configure an ACTIVE Play Console INAPP item matching PRODUCT_ID.
 * This example only validates ownership from Play Billing on-device;
 * stronger fraud protection requires server-side purchase-token verification.
 */
final class BillingManager {
    static final String PRODUCT_ID="pdfforge_premium_lifetime";
    interface Listener {
        void changed();
        void message(int textResource);
    }
    private final Activity activity;
    private final Listener listener;
    private final BillingClient client;
    private ProductDetails product;
    private boolean purchased;
    private boolean connecting;
    private boolean disposed;
    private boolean loadingProduct;
    private int availabilityMessage=R.string.premium_connecting;

    BillingManager(Activity activity,Listener listener) {
        this.activity=activity;
        this.listener=listener;
        // Preserve last Play-confirmed access when the user's phone is offline.
        purchased=activity.getSharedPreferences("pdf_forge",Context.MODE_PRIVATE)
                .getBoolean("premium_owned_cached",false);
        client=BillingClient.newBuilder(activity)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .setListener((result,purchases)->{
                    if(disposed)return;
                    if(result.getResponseCode()==BillingClient.BillingResponseCode.OK && purchases!=null){
                        processPurchases(purchases);
                        refresh();
                    }else if(result.getResponseCode()!=BillingClient.BillingResponseCode.USER_CANCELED){
                        notifyMessage(R.string.premium_purchase_failed);
                    }
                }).build();
    }

    boolean isPremium(){return purchased;}
    String price(){
        if(product==null)return null;
        List<ProductDetails.OneTimePurchaseOfferDetails> offers=product.getOneTimePurchaseOfferDetailsList();
        if(offers==null || offers.isEmpty())return null;
        return offers.get(0).getFormattedPrice();
    }
    boolean isLoading(){return connecting || loadingProduct;}
    int availabilityMessage(){return availabilityMessage;}

    boolean canBuy(){
        return client.isReady() && product!=null && price()!=null;
    }

    void connect(){
        if(disposed||client.isReady()||connecting)return;
        connecting=true;
        availabilityMessage=R.string.premium_connecting;
        notifyChanged();
        client.startConnection(new BillingClientStateListener(){
            @Override public void onBillingSetupFinished(BillingResult result){
                connecting=false;
                if(disposed)return;
                if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                    refresh();
                }else {
                    availabilityMessage=R.string.premium_connection_failed;
                    notifyChanged();
                }
            }
            @Override public void onBillingServiceDisconnected(){
                connecting=false;
                product=null;
                availabilityMessage=R.string.premium_connection_failed;
                notifyChanged();
            }
        });
    }

    void refresh(){
        if(disposed)return;
        if(!client.isReady()){connect();return;}
        loadProduct();
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build(),(result,purchases)->{
            if(disposed)return;
            if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK) return;
            processPurchases(purchases);
        });
    }

    private void processPurchases(List<Purchase> purchases) {
        boolean owns=false;
        for(Purchase p:purchases){
            if(p.getProducts().contains(PRODUCT_ID) &&
                    p.getPurchaseState()==Purchase.PurchaseState.PURCHASED){
                owns=true;
                if(!p.isAcknowledged()){
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(p.getPurchaseToken()).build(),result->{
                        // Google Play retries acknowledgements that transiently fail.
                        if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK)
                            notifyMessage(R.string.premium_ack_pending);
                    });
                }
            }
        }
        purchased=owns; // also revoke refunded/cancelled one-time products on successful query
        activity.getSharedPreferences("pdf_forge",Context.MODE_PRIVATE)
                .edit().putBoolean("premium_owned_cached",owns).apply();
        notifyChanged();
    }

    private void loadProduct(){
        if(disposed || loadingProduct)return;
        loadingProduct=true;
        product=null;
        availabilityMessage=R.string.premium_connecting;
        notifyChanged();
        QueryProductDetailsParams params=QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP).build()))
            .build();
        client.queryProductDetailsAsync(params,(result,data)->{
            if(disposed)return;
            loadingProduct=false;
            if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                product=null;
                for(ProductDetails found:data.getProductDetailsList())
                    if(PRODUCT_ID.equals(found.getProductId())){product=found;break;}
                availabilityMessage=R.string.premium_offer_unavailable;
            }else {
                availabilityMessage=R.string.premium_connection_failed;
            }
            notifyChanged();
        });
    }

    void purchase(){
        if(disposed)return;
        if(purchased){notifyMessage(R.string.premium_already_owned);return;}
        if(!canBuy()){
            if(!isLoading())refresh();
            return;
        }
        List<ProductDetails.OneTimePurchaseOfferDetails> offers=product.getOneTimePurchaseOfferDetailsList();
        String offerToken=offers.get(0).getOfferToken();
        BillingFlowParams.ProductDetailsParams.Builder detail=BillingFlowParams.ProductDetailsParams
                .newBuilder().setProductDetails(product);
        if(offerToken!=null && !offerToken.isEmpty())detail.setOfferToken(offerToken);
        BillingFlowParams flow=BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(detail.build())).build();
        BillingResult result=client.launchBillingFlow(activity,flow);
        if(result.getResponseCode()==BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED){
            refresh();
        }else if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK
                && result.getResponseCode()!=BillingClient.BillingResponseCode.USER_CANCELED){
            notifyMessage(R.string.premium_purchase_failed);
            refresh();
        }
    }

    void restore(){
        if(!client.isReady()) {connect();notifyMessage(R.string.premium_connecting);return;}
        refresh();
        notifyMessage(R.string.premium_checking);
    }

    private void notifyChanged(){
        activity.runOnUiThread(()->{if(!disposed)listener.changed();});
    }
    private void notifyMessage(int message){
        activity.runOnUiThread(()->{if(!disposed)listener.message(message);});
    }
    void close(){
        disposed=true;
        client.endConnection();
    }
}

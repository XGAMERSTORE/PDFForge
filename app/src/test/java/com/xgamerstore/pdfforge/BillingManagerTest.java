package com.xgamerstore.pdfforge;

import android.app.Activity;
import android.content.SharedPreferences;
import com.android.billingclient.api.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BillingManagerTest {
    private BillingClient client;
    private BillingManager manager;
    private MockedStatic<BillingClient> factory;

    @Before public void setup(){
        Activity activity=mock(Activity.class);
        SharedPreferences prefs=mock(SharedPreferences.class);
        when(activity.getSharedPreferences(anyString(),anyInt())).thenReturn(prefs);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;})
                .when(activity).runOnUiThread(any(Runnable.class));
        client=mock(BillingClient.class);
        when(client.isReady()).thenReturn(true);
        BillingClient.Builder builder=mock(BillingClient.Builder.class,RETURNS_SELF);
        when(builder.build()).thenReturn(client);
        factory=mockStatic(BillingClient.class);
        factory.when(()->BillingClient.newBuilder(activity)).thenReturn(builder);
        manager=new BillingManager(activity,mock(BillingManager.Listener.class));
    }
    @After public void cleanup(){manager.close();factory.close();}

    private void failProductRequest(){
        ArgumentCaptor<ProductDetailsResponseListener> callback=
                ArgumentCaptor.forClass(ProductDetailsResponseListener.class);
        verify(client).queryProductDetailsAsync(any(),callback.capture());
        BillingResult error=mock(BillingResult.class);
        when(error.getResponseCode()).thenReturn(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE);
        callback.getValue().onProductDetailsResponse(error,null);
    }

    @Test public void purchaseRetriesFailedQueryOnExistingConnection(){
        manager.refresh();
        failProductRequest();
        assertFalse(manager.isLoading());
        assertFalse(manager.canBuy());
        assertEquals(R.string.premium_connection_failed,manager.availabilityMessage());
        manager.purchase();
        verify(client,times(2)).queryProductDetailsAsync(any(),any());
        verify(client,never()).launchBillingFlow(any(),any());
        assertTrue(manager.isLoading());
    }

    @Test public void repeatedTapsDoNotDuplicatePendingProductQuery(){
        manager.refresh();
        manager.purchase();
        manager.purchase();
        verify(client,times(1)).queryProductDetailsAsync(any(),any());
    }

    @Test public void returningToAppRetriesFailedProductQuery(){
        manager.refresh();
        failProductRequest();
        manager.refresh();
        verify(client,times(2)).queryProductDetailsAsync(any(),any());
        assertFalse(manager.isPremium());
    }
}

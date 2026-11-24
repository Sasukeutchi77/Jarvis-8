// OpenJarvis - Expo Go Mobile Entry Wrapper
import React, { useRef, useState } from 'react';
import { StyleSheet, View, StatusBar, SafeAreaView, ActivityIndicator, Text, BackHandler } from 'react-native';
import { WebView } from 'react-native-webview';

// The web server is deliberately supplied at build time. This prevents a
// packaged client from silently sending conversations to a third-party host.
const APP_URL = (process.env.EXPO_PUBLIC_APP_URL || '').trim();
const isValidAppUrl = (() => {
  try {
    return ['http:', 'https:'].includes(new URL(APP_URL).protocol);
  } catch {
    return false;
  }
})();

export default function App() {
  const webViewRef = useRef(null);
  const [canGoBack, setCanGoBack] = useState(false);
  const [loading, setLoading] = useState(true);

  React.useEffect(() => {
    const onBackPress = () => {
      if (canGoBack && webViewRef.current) {
        webViewRef.current.goBack();
        return true;
      }
      return false;
    };

    const subscription = BackHandler.addEventListener('hardwareBackPress', onBackPress);
    return () => subscription.remove();
  }, [canGoBack]);

  if (!isValidAppUrl) {
    return (
      <SafeAreaView style={styles.container}>
        <StatusBar barStyle="light-content" backgroundColor="#020617" />
        <View style={styles.setupContainer}>
          <Text style={styles.setupTitle}>Serveur OpenJarvis non configuré</Text>
          <Text style={styles.setupText}>
            Définissez EXPO_PUBLIC_APP_URL avec l’adresse HTTPS de votre serveur, puis reconstruisez l’application.
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#020617" />
      <WebView
        ref={webViewRef}
        source={{ uri: APP_URL }}
        style={styles.webview}
        onNavigationStateChange={(navState) => setCanGoBack(navState.canGoBack)}
        onLoadEnd={() => setLoading(false)}
        allowsInlineMediaPlayback
        mediaPlaybackRequiresUserAction={false}
        javaScriptEnabled
        domStorageEnabled
        allowsBackForwardNavigationGestures
        startInLoadingState
        renderLoading={() => (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color="#06b6d4" />
            <Text style={styles.loadingText}>Initialisation d'OpenJarvis...</Text>
          </View>
        )}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#020617',
  },
  webview: {
    flex: 1,
    backgroundColor: '#020617',
  },
  loadingContainer: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#020617',
  },
  loadingText: {
    marginTop: 12,
    color: '#06b6d4',
    fontSize: 14,
    fontWeight: '600',
    letterSpacing: 0.5,
  },
  setupContainer: {
    flex: 1,
    justifyContent: 'center',
    padding: 28,
  },
  setupTitle: {
    color: '#f8fafc',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 12,
  },
  setupText: {
    color: '#cbd5e1',
    fontSize: 15,
    lineHeight: 22,
  },
});


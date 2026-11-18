package com.nearapp.aicatalogbackend.setup;

import com.google.firebase.auth.FirebaseAuth;
import com.nearapp.aicatalogbackend.config.FirebaseConfig;
import java.util.HashMap;

public final class AdminClaimsCommand {
  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !(args[0].equals("--uid") || args[0].equals("--email")))
      throw new IllegalArgumentException("Usage: --uid UID | --email EMAIL");
    var app = FirebaseConfig.initialize(System.getenv("FIREBASE_PROJECT_ID"));
    try {
      var auth = FirebaseAuth.getInstance(app);
      var user = args[0].equals("--uid") ? auth.getUser(args[1]) : auth.getUserByEmail(args[1]);
      var claims = new HashMap<String, Object>(user.getCustomClaims());
      claims.put("admin", true);
      auth.setCustomUserClaims(user.getUid(), claims);
      System.out.println("ADMIN claim assigned. Refresh the user's Firebase ID token.");
    } finally {
      app.delete();
    }
  }
}

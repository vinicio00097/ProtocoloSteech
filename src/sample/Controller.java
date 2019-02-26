package sample;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;

public class Controller implements Initializable {
    public TextArea incomingWord;
    public Button send;
    public TextArea errorsDisplay;
    public AnchorPane container;
    public Button conectarCliente;
    public Button hacerServidor;
    private Stack<TramaItem> missingTramas=new Stack<>();
    private Integer errores=7;
    private String palabra="cuando desperte el dragon aun estaba a mi lado";
    private String generator="1101";
    private List<TramaItem> encodedWord=new ArrayList<>();
    private List<TramaItem> encodedWordToSend=new ArrayList<>();
    private BinaryTransmission centralGroup=new BinaryTransmission();
    private BinaryTransmission groupToSend=new BinaryTransmission();
    private Server server=new Server();
    private Client client=new Client();
    private Kryo kryoServer=server.getKryo();
    private Kryo kryoClient=client.getKryo();
    private HashMap<String,String> headerReceived;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        kryoServer.register(String[].class);
        kryoServer.register(HashMap.class);
        kryoServer.register(Map.class);
        kryoClient.register(String[].class);
        kryoClient.register(HashMap.class);
        kryoClient.register(Map.class);

        send.setText("Verificar");
        errorsDisplay.setFocusTraversable(false);
        errorsDisplay.setStyle("");

        centralGroup.setWord(encodedWord);
        centralGroup.doTransformation(palabra);

        hacerServidor.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                try {
                    server.start();
                    server.bind(2000);
                    server.addListener(new Listener(){
                        @Override
                        public void received(Connection connection, Object object) {
                            super.received(connection, object);
                            if(object instanceof String[]){
                                String[] tramaReceived=(String[]) object;
                                System.out.println(tramaReceived[0]+" "+tramaReceived[1]+" "+tramaReceived[2]);
                            }
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        conectarCliente.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                try {
                    client.connect(1000,"localhost",2000);
                    client.addListener(new Listener(){
                        @Override
                        public void received(Connection connection, Object object) {
                            super.received(connection, object);
                            if(object instanceof String[]){
                                String[] tramaReceived=(String[]) object;
                                List<String> preparedTrama=prepareTramaReceived(tramaReceived[1]);
                                Object[] response=verificacionCRC2(preparedTrama,prepararOperacion(generator,preparedTrama));
                            }else{
                                if(object instanceof HashMap){
                                    headerReceived=(HashMap<String, String>) object;
                                    System.out.println(headerReceived.get(" "));
                                }else {
                                    if(object instanceof String){
                                        switch (headerReceived.get(object.toString())){
                                            case "Inicio":{
                                                System.out.println("Inició");
                                            }break;
                                            case "Fin":{
                                                System.out.println("Finalizó");
                                            }break;
                                        }
                                    }
                                }
                            }
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        send.setOnAction(event -> {
            if(incomingWord.getText().length()>0){
                groupToSend.resetAll();
                missingTramas.clear();
                groupToSend.setWord(encodedWordToSend);
                groupToSend.doTransformation(incomingWord.getText());


                if(validar(encodedWord,encodedWordToSend,0,0)){

                    server.sendToAllTCP(centralGroup.getSpecialOperations());
                    server.sendToAllTCP(centralGroup.getSpecialOperations().get("Inicio"));
                    for(TramaItem tramaItem:encodedWordToSend){
                        if(!tramaItem.getTramaIndex().equals(" ")){
                            List<String> preparedTrama=prepararTrama(tramaItem.getBinaryLetter());
                            Object[] response=verificacionCRC(preparedTrama,prepararOperacion(generator,preparedTrama));

                            if(Boolean.valueOf(response[0].toString())){
                                tramaItem.setBinaryLetter(tramaItem.getBinaryLetter()+complemento((ArrayList<String>)response[1]));

                                if(server.getConnections().length>0){
                                    server.sendToAllTCP(new String[]{tramaItem.getWordIndex(),tramaItem.getBinaryLetter(),tramaItem.getTramaIndex()});
                                }
                            }
                        }
                    }

                    server.sendToAllTCP(centralGroup.getSpecialOperations().get("Fin"));
                    errorsDisplay.appendText(incomingWord.getText()+"\n");
                }else{
                    StringBuilder errorMsg=new StringBuilder("Hubo error, cadena incompleta \n");

                    if(missingTramas.size()>0){
                        errorsDisplay.appendText(errorMsg.toString());
                    }else{
                        errorsDisplay.appendText("Hubo error, cadena con longitud de mas. \n");
                    }
                }
            }
        });
    }

    private boolean validar(List<TramaItem> centralGroup,List<TramaItem> groupToSend,int index1,int index2){
        if(index2==groupToSend.size()){
            missingTramas.add(centralGroup.get(index1));

            if(missingTramas.size()>=errores){
                return false;
            }
            if(index1<=centralGroup.size()){
                index1++;
            }
        }else{
            if(!centralGroup.get(index1).getBinaryLetter().equals(groupToSend.get(index2).getBinaryLetter())){
                missingTramas.push(centralGroup.get(index1));

                if(missingTramas.size()>=errores){
                    return false;
                }

                if(centralGroup.get(index1+1).getTramaIndex().equals(" ")){
                    index1+=getSpaceAmount(index1+1,0);
                }else{
                    if(index1<=centralGroup.size()){
                        index1++;
                    }
                }
            }else {
                if(index1<=centralGroup.size()){
                    index1++;
                }
                if(index2<=groupToSend.size()){
                    index2++;
                }
            }
        }

        if(index1==centralGroup.size()){
            return index2 == groupToSend.size();
        }else{
            if(index2==groupToSend.size()){
                return validar(centralGroup,groupToSend,index1,index2);
            }else{
                return validar(centralGroup,groupToSend,index1,index2);
            }
        }
    }

    private int getSpaceAmount(int index,int amount){
        if(encodedWord.get(index).getTramaIndex().equals(" ")){
            amount++;
            index++;

            if(index==encodedWord.size()){
                return amount;
            }else{
                return getSpaceAmount(index,amount);
            }
        }else{
            return amount;
        }
    }

    private Object[] verificacionCRC(List<String> trama,List<String> operacion){
        List<String> newOperacion=new ArrayList<>();
        boolean hasOne=false;

        for(int count=0;count<trama.size();count++){
            if(!trama.get(count).equals("+")){
                if(!operacion.get(count).equals("+")){
                    if(operacion.get(count).equals(trama.get(count))){
                        if(!hasOne){
                            newOperacion.add("+");
                        }else{
                            newOperacion.add("0");
                        }
                    }else{
                        if(!hasOne){
                            hasOne=true;
                        }
                        newOperacion.add("1");
                    }
                }
            }else{
                if(!hasOne){
                    newOperacion.add("+");
                }
            }
        }

        int actualSize=newOperacion.size();
        for(int count=actualSize;count<trama.size();count++){
            newOperacion.add(trama.get(count));
        }

        if(!operacion.get(operacion.size() - 1).equals("+")){
            return new Object[]{true,newOperacion};
        }else{
            return verificacionCRC(newOperacion,prepararOperacion(generator,newOperacion));
        }
    }

    private Object[] verificacionCRC2(List<String> trama,List<String> operacion){
        List<String> newOperacion=new ArrayList<>();
        boolean hasOne=false;

        for(int count=0;count<trama.size();count++){
            if(!trama.get(count).equals("+")){
                if(!operacion.get(count).equals("+")){
                    if(operacion.get(count).equals(trama.get(count))){
                        if(!hasOne){
                            newOperacion.add("+");
                        }else{
                            newOperacion.add("0");
                        }
                    }else{
                        if(!hasOne){
                            hasOne=true;
                        }
                        newOperacion.add("1");
                    }
                }
            }else{
                if(!hasOne){
                    newOperacion.add("+");
                }
            }
        }

        int actualSize=newOperacion.size();
        for(int count=actualSize;count<trama.size();count++){
            newOperacion.add(trama.get(count));
        }

        if(!operacion.get(operacion.size() - 1).equals("+")){
            return new Object[]{true,newOperacion};
        }else{
            return verificacionCRC2(newOperacion,prepararOperacion(generator,newOperacion));
        }
    }

    private List<String> prepararTrama(String trama){
        List<String> arrayTrama=new ArrayList<>();

        for(int count=0;count<trama.length()+(generator.length());count++){
            if(count>=trama.length()){
                arrayTrama.add("0");
            }else{
                arrayTrama.add(String.valueOf(trama.charAt(count)));
            }
        }

        return arrayTrama;
    }

    private List<String> prepareTramaReceived(String trama){
        List<String> arrayTrama=new ArrayList<>();

        for(int count=0;count<trama.length();count++){
            arrayTrama.add(String.valueOf(trama.charAt(count)));
        }

        return arrayTrama;
    }

    private List<String> prepararOperacion(String generator,List<String> trama){
        List<String> preparedGenerator=new ArrayList<>();
        int count2=0;

        for (String aTrama : trama) {
            if (!aTrama.equals("+")) {
                if (count2 < generator.length()) {
                    preparedGenerator.add(String.valueOf(generator.charAt(count2)));
                    count2++;
                } else {
                    preparedGenerator.add("+");
                }
            } else {
                preparedGenerator.add("+");
            }
        }

        return preparedGenerator;
    }

    private String complemento(List<String> residuo){
        List<String> total=residuo.subList(((residuo.size()-generator.length())+1),residuo.size());
        StringBuilder returnStatement=new StringBuilder();
        for(int count=0;count<total.size();count++){
            if(total.get(count).equals("+")){
                returnStatement.append("0");
            }else{
                returnStatement.append(total.get(count));
            }
        }

        return returnStatement.toString();
    }
}

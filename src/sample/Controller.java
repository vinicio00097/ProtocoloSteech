package sample;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.util.Duration;

import java.io.FileWriter;
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
    public TableView tablapalabra;
    public TextField frase;
    private Stack<TramaItem> missingTramas=new Stack<>();
    private Stack<TramaItem> overTramas=new Stack<>();
    private Stack<TramaItem> wrongTramas=new Stack<>();
    private Integer errores=7;
    private String palabra="cuando desperte el dragon aun estaba a mi lado";
    private String generator="1101";
    private List<TramaItem> encodedWord=new ArrayList<>();
    private List<TramaItem> encodedWordToSend=new ArrayList<>();
    private List<TramaItem> encodedWordReceived=new ArrayList<>();
    private BinaryTransmission centralGroup=new BinaryTransmission();
    private BinaryTransmission groupToSend=new BinaryTransmission();
    private Server server=new Server();
    private Client client=new Client();
    private Kryo kryoServer=server.getKryo();
    private Kryo kryoClient=client.getKryo();
    private HashMap<String,String> headerReceived;
    private int intentos=0;
    private StringBuilder crcSteps;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        kryoServer.register(String[].class);
        kryoServer.register(HashMap.class);
        kryoServer.register(Map.class);
        kryoClient.register(String[].class);
        kryoClient.register(HashMap.class);
        kryoClient.register(Map.class);

        send.setText("Verificar");
        frase.setText(palabra);
        errorsDisplay.setFocusTraversable(false);
        errorsDisplay.setStyle("");

        centralGroup.setWord(encodedWord);
        centralGroup.doTransformation(palabra);

        frase.setOnMouseClicked(event ->{
            if(event.getButton()==MouseButton.PRIMARY){
                intentos++;
                if(intentos==3){
                    intentos=0;

                    frase.setEditable(true);
                }
            }
        });

        frase.focusedProperty().addListener((observable, oldValue, newValue) -> {
            groupToSend.resetAll();
            centralGroup.resetAll();
            missingTramas.clear();
            overTramas.clear();

            palabra=frase.getText();
            centralGroup.setWord(encodedWord);
            centralGroup.doTransformation(palabra);

            intentos=0;
            frase.setEditable(false);
        });

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
                            if(object instanceof String){
                                errorsDisplay.appendText("Cliente "+connection.getID()+" dice: "+centralGroup.getSpecialOperations().get(object)+"\n");
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
                    client.start();
                    client.connect(1000,"localhost",2000);
                    client.addListener(new Listener(){
                        @Override
                        public void received(Connection connection, Object object) {
                            super.received(connection, object);
                            if(object instanceof String[]){
                                String[] tramaReceived=(String[]) object;

                                if(!headerReceived.containsKey(tramaReceived[1])){
                                    List<String> preparedTrama=prepareTramaReceived(tramaReceived[1]);
                                    Object[] response=verificacionCRC2(preparedTrama,prepararOperacion(generator,preparedTrama));

                                    if(Boolean.valueOf(response[0].toString())){
                                        int charCode = Integer.parseInt(tramaReceived[1].substring(0,tramaReceived[1].length()-3), 2);
                                        String letra = Character.toString((char)charCode);

                                        TramaItem newTrama=new TramaItem(
                                                new SimpleStringProperty(tramaReceived[0]),
                                                new SimpleStringProperty(tramaReceived[1].substring(0,tramaReceived[1].length()-3)),
                                                new SimpleStringProperty(letra),
                                                new SimpleStringProperty(tramaReceived[2])
                                        );

                                        encodedWordReceived.add(newTrama);

                                        if(!isValid((List<String>) response[1])){
                                            wrongTramas.push(newTrama);
                                        }
                                    }
                                }else{
                                    encodedWordReceived.add(new TramaItem(
                                            new SimpleStringProperty(" "),
                                            new SimpleStringProperty(tramaReceived[1]),
                                            new SimpleStringProperty(" "),
                                            new SimpleStringProperty(" ")
                                    ));
                                }
                            }else{
                                if(object instanceof HashMap){
                                    headerReceived=(HashMap<String, String>) object;
                                }else {
                                    if(object instanceof String){
                                        switch (headerReceived.get(object.toString())){
                                            case "Inicio":{
                                                missingTramas.clear();
                                                overTramas.clear();
                                                encodedWordReceived.clear();
                                                crcSteps=new StringBuilder();

                                                errorsDisplay.appendText("Iniciando conversación"+"\n");
                                            }break;
                                            case "Fin":{
                                                errorsDisplay.appendText(crcSteps+"\n\n");

                                                if(wrongTramas.size()>0){
                                                    client.sendTCP(headerReceived.get("No"));
                                                }else{
                                                    validar(encodedWord,encodedWordReceived,0,0);
                                                    if(missingTramas.size()==0&&overTramas.size()==0){
                                                        errorsDisplay.appendText(getMessage()+"\n\n");
                                                        client.sendTCP(headerReceived.get("Si"));
                                                    }else{
                                                        if(missingTramas.size()>0){
                                                            if(overTramas.size()>0){
                                                                errorsDisplay.appendText("Letras faltantes: \n"+getMissingTramas());
                                                                errorsDisplay.appendText("Letras extra: \n"+getOverTramas()+"\n\n");
                                                            }else{
                                                                errorsDisplay.appendText("Letras faltantes: \n"+getMissingTramas()+"\n\n");
                                                            }
                                                        }else{
                                                            errorsDisplay.appendText("Letras extra: \n"+getOverTramas()+"\n\n");
                                                        }

                                                        client.sendTCP(headerReceived.get("No"));
                                                    }
                                                }

                                                //printToFile(crcSteps,"valicaciónCRC.txt");
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
                initTable(tablapalabra,encodedWordToSend);

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
                    }else{
                        if(server.getConnections().length>0){
                            server.sendToAllTCP(new String[]{tramaItem.getWordIndex(),tramaItem.getBinaryLetter(),tramaItem.getTramaIndex()});
                        }
                    }
                }

                server.sendToAllTCP(centralGroup.getSpecialOperations().get("Fin"));
                errorsDisplay.appendText(incomingWord.getText()+"\n");
            }
        });
    }

    private void initTable(TableView tabla,List<TramaItem> data){
        tabla.getItems().clear();
        tabla.getColumns().clear();

        List<String[]> etiquetas=new ArrayList<>();
        etiquetas.add(new String[]{"Palabra Index","wordIndex"});
        etiquetas.add(new String[]{"Letra Binaria","binaryLetter"});
        etiquetas.add(new String[]{"Letra","letter"});
        etiquetas.add(new String[]{"Trama Index","tramaIndex"});
        tabla.setEditable(false);

        for(String[] item:etiquetas){
            TableColumn column=new TableColumn(item[0]);
            column.setSortable(false);

            column.setCellValueFactory(new PropertyValueFactory<TramaItem,String>(item[1]));

            if(item[0].equals("Letra")){
                column.setPrefWidth(90);
            }else{
                column.setPrefWidth(130);
            }
            tabla.getColumns().add(column);
        }

        tabla.setItems(FXCollections.observableArrayList(data));
    }

    private boolean validar(List<TramaItem> centralGroup,List<TramaItem> groupToSend,int index1,int index2){
        if(index2==groupToSend.size()){
            missingTramas.add(centralGroup.get(index1));

            if(index1<=centralGroup.size()){
                index1++;
            }
        }else{
            if(!centralGroup.get(index1).getBinaryLetter().equals(groupToSend.get(index2).getBinaryLetter())){
                missingTramas.push(centralGroup.get(index1));

                if((index1+1)<centralGroup.size()){
                    if(centralGroup.get(index1+1).getTramaIndex().equals(" ")){
                        index1+=getSpaceAmount(index1+1,0);
                    }else{
                        if(index1<=centralGroup.size()){
                            index1++;
                        }
                    }
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
            for(int count=index1;count<groupToSend.size();count++){
                overTramas.add(groupToSend.get(count));
            }

            return true;
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

        if(getBitsAmount(newOperacion)<generator.length()){
            return new Object[]{true,newOperacion};
        }else{
            return verificacionCRC(newOperacion,prepararOperacion(generator,newOperacion));
        }
    }

    private Object[] verificacionCRC2(List<String> trama,List<String> operacion){
        List<String> newOperacion=new ArrayList<>();
        boolean hasOne=false;

        crcSteps.append(trama).append("\n").append(operacion).append("\n");

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

        if(getBitsAmount(newOperacion)<generator.length()){
            crcSteps.append(newOperacion).append("\n\n");
            return new Object[]{true,newOperacion};
        }else{
            return verificacionCRC2(newOperacion,prepararOperacion(generator,newOperacion));
        }
    }

    private int getBitsAmount(List<String> trama){
        int counter=0;

        for(String item:trama){
            if(!item.equals("+")){
                counter++;
            }
        }

        return counter;
    }

    private List<String> prepararTrama(String trama){
        List<String> arrayTrama=new ArrayList<>();

        for(int count=0;count<trama.length()+(generator.length()-1);count++){
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

    private boolean isValid(List<String> residuo){
        boolean isValid=true;

        for(String item:residuo){
            if(!item.equals("+")){
                if(Integer.parseInt(item)>0){
                    isValid=!isValid;
                }
            }
        }

        return isValid;
    }

    private String getMissingTramas(){
        StringBuilder stringBuilder=new StringBuilder();

        for(TramaItem item:missingTramas){
            stringBuilder.append(item.getLetter()).append("\n");
        }

        return stringBuilder.toString();
    }

    private String getOverTramas(){
        StringBuilder stringBuilder=new StringBuilder();

        for(TramaItem item:overTramas){
            stringBuilder.append(item.getLetter()).append("\n");
        }

        return stringBuilder.toString();
    }

    private String getMessage(){
        StringBuilder stringBuilder=new StringBuilder();

        for(TramaItem item:encodedWordReceived){
            stringBuilder.append(item.getLetter());
        }

        return stringBuilder.toString();
    }
}
